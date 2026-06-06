package com.timetable.operator.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.agent.application.context.AiContextPackageBuilder;
import com.timetable.operator.agent.application.decision.AiDecisionPackageFactory;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AiDecisionPackage;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.api.UserActionRequiredException;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.application.ScheduleBlockRules;
import com.timetable.operator.schedule.application.ScheduleNoteSanitizer;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.application.ProviderWriteOutboxService.EnqueueResult;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RescheduleSuggestionService {

    private static final String PROVIDER_WRITE_RECONNECT_REQUIRED_DETAIL = "Google 쓰기는 재연동 후 처리됩니다.";
    private static final String PROVIDER_WRITE_NO_CONNECTION_DETAIL = "Google 연동이 없어 Google에는 반영되지 않았습니다.";
    private static final int AI_MESSAGE_HISTORY_LIMIT = 5;
    private static final int AI_HISTORY_FIELD_MAX_CHARS = 1000;
    private static final int DEFAULT_AI_FUTURE_CALENDAR_DAYS = 3;
    private static final int MIN_AVAILABILITY_WINDOW_MINUTES = 30;
    private static final int MAX_AVAILABILITY_WINDOWS = 24;
    private static final int CANONICAL_TITLE_MAX_LENGTH = 255;

    private final RescheduleSuggestionRepository rescheduleSuggestionRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final ScheduleBlockRules scheduleBlockRules;
    private final ProviderWriteOutboxService providerWriteOutboxService;
    private final AiRequestAgentService aiRequestAgentService;
    private final AiContextPackageBuilder aiContextPackageBuilder;
    private final AiDecisionPackageFactory aiDecisionPackageFactory;

    @Transactional
    public RescheduleSuggestionResponse createManualSuggestion(ManualRescheduleRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        StructuredAiCommandBatch batch = resolveManualSuggestionBatch(user, request);

        return createSuggestion(
                user.getId(),
                RescheduleSuggestionTriggerType.from(request.triggerType()),
                batch.summary(),
                request.reason(),
                batch.explanation(),
                batch
        );
    }

    private StructuredAiCommandBatch resolveManualSuggestionBatch(AppUser user, ManualRescheduleRequest request) {
        AiRescheduleClient.RescheduleAiContext context = buildAiContext(user, request);
        StructuredAiCommandBatch batch = aiRequestAgentService.resolveManualRequest(user, request.reason(), context);
        return enrichBatchWithDecisionMetadata(
                batch,
                user.getTimezone(),
                context.request() == null ? null : context.request().resolvedRangeStart(),
                context.request() == null ? null : context.request().resolvedRangeEnd()
        );
    }

    @Transactional
    public RescheduleSuggestionResponse createChatSuggestion(
            ChatCommandNormalizationService.NormalizedChatCommand normalizedChatCommand
    ) {
        AppUser user = currentUserProvider.getCurrentUser();
        StructuredAiCommandBatch batch = normalizedChatCommand.requiresAiPlanning()
                ? resolveManualSuggestionBatch(
                        user,
                        new ManualRescheduleRequest(
                                RescheduleSuggestionTriggerType.MANUAL_REQUEST.wireValue(),
                                null,
                                null,
                                normalizedChatCommand.normalizedMessage()
                        )
                )
                : enrichBatchWithDecisionMetadata(
                        aiRequestAgentService.resolvePrebuiltCommandBatch(
                                user,
                                normalizedChatCommand.commandBatch()
                        ),
                        user.getTimezone(),
                        null,
                        null
                );
        return createSuggestion(
                user.getId(),
                RescheduleSuggestionTriggerType.MANUAL_REQUEST,
                batch.summary(),
                normalizedChatCommand.normalizedMessage(),
                batch.explanation(),
                batch
        );
    }

    @Transactional
    public RescheduleSuggestionResponse createSuggestionFromBatch(
            RescheduleSuggestionTriggerType triggerType,
            String summary,
            String reason,
            String explanation,
            StructuredAiCommandBatch batch
    ) {
        AppUser user = currentUserProvider.getCurrentUser();
        return createSuggestion(
                user.getId(),
                triggerType,
                summary,
                reason,
                explanation,
                enrichBatchWithDecisionMetadata(batch, user.getTimezone(), null, null)
        );
    }

    @Transactional(readOnly = true)
    public List<RescheduleSuggestionResponse> getCurrentUserSuggestions() {
        AppUser user = currentUserProvider.getCurrentUser();
        return rescheduleSuggestionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RescheduleSuggestionResponse getCurrentUserSuggestion(UUID suggestionId) {
        AppUser user = currentUserProvider.getCurrentUser();
        return toResponse(getOwnedSuggestion(user.getId(), suggestionId));
    }

    @Transactional
    public RescheduleSuggestionResponse applySuggestion(UUID suggestionId, SuggestionDecisionRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        RescheduleSuggestion suggestion = getOwnedSuggestionForUpdate(user.getId(), suggestionId);
        if (suggestion.getStatus() != RescheduleSuggestionStatus.PENDING) {
            throw new IllegalStateException("대기 중인 suggestion만 적용할 수 있습니다.");
        }

        StructuredAiCommandBatch batch = readBatch(suggestion.getSuggestionPayload());
        ZoneId userZone = AiLocalDateTimeParser.resolveUserZone(user.getTimezone());
        List<AppliedCommandSnapshot> snapshots = new ArrayList<>();
        for (StructuredAiCommand command : batch.commands()) {
            snapshots.add(applyCommandOrAbort(user.getId(), userZone, command));
        }
        SuggestionExecutionSummaryResponse executionSummary = summarizeSnapshots(snapshots);
        if (executionSummary == null || executionSummary.appliedCount() == 0) {
            throw new UserActionRequiredException("적용할 수 있는 변경이 없습니다. 요청을 더 구체적으로 다시 보내주세요.");
        }

        suggestion.setStatus(RescheduleSuggestionStatus.APPLIED);
        suggestion.setAppliedAt(Instant.now());
        suggestion.setExecutionSnapshot(writeJson(snapshots));
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            suggestion.setDecisionReason(request.reason().trim());
        }
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    @Transactional
    public RescheduleSuggestionResponse rejectSuggestion(UUID suggestionId, SuggestionDecisionRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        RescheduleSuggestion suggestion = getOwnedSuggestionForUpdate(user.getId(), suggestionId);
        if (suggestion.getStatus() != RescheduleSuggestionStatus.PENDING) {
            throw new IllegalStateException("대기 중인 suggestion만 거절할 수 있습니다.");
        }

        suggestion.setStatus(RescheduleSuggestionStatus.REJECTED);
        suggestion.setRejectedAt(Instant.now());
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            suggestion.setDecisionReason(request.reason().trim());
        }
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    @Transactional
    public RescheduleSuggestionResponse revertSuggestion(UUID suggestionId, String revertReason) {
        AppUser user = currentUserProvider.getCurrentUser();
        RescheduleSuggestion suggestion = getOwnedSuggestionForUpdate(user.getId(), suggestionId);
        if (suggestion.getStatus() != RescheduleSuggestionStatus.APPLIED) {
            throw new IllegalStateException("적용된 suggestion만 되돌릴 수 있습니다.");
        }

        List<AppliedCommandSnapshot> snapshots = readSnapshots(suggestion.getExecutionSnapshot());
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            revertSnapshot(user.getId(), snapshots.get(index));
        }

        suggestion.setStatus(RescheduleSuggestionStatus.REVERTED);
        suggestion.setRevertedAt(Instant.now());
        if (revertReason != null && !revertReason.isBlank()) {
            suggestion.setDecisionReason(revertReason.trim());
        }
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    private RescheduleSuggestionResponse createSuggestion(
            UUID userId,
            RescheduleSuggestionTriggerType triggerType,
            String summary,
            String reason,
            String explanation,
            StructuredAiCommandBatch batch
    ) {
        RescheduleSuggestion suggestion = new RescheduleSuggestion();
        suggestion.setUserId(userId);
        suggestion.setTriggerType(triggerType);
        suggestion.setStatus(RescheduleSuggestionStatus.PENDING);
        suggestion.setSummary(summary);
        suggestion.setReason(reason);
        suggestion.setOriginalRequest(reason);
        suggestion.setExplanation(explanation);
        suggestion.setSuggestionPayload(writeJson(batch));
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    private RescheduleSuggestion getOwnedSuggestion(UUID userId, UUID suggestionId) {
        return rescheduleSuggestionRepository.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 suggestion을 찾을 수 없습니다."));
    }

    private RescheduleSuggestion getOwnedSuggestionForUpdate(UUID userId, UUID suggestionId) {
        return rescheduleSuggestionRepository.findByIdAndUserIdForUpdate(suggestionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 suggestion을 찾을 수 없습니다."));
    }

    private RescheduleSuggestionResponse toResponse(RescheduleSuggestion suggestion) {
        StructuredAiCommandBatch batch = readBatch(suggestion.getSuggestionPayload());
        AiDecisionPackage decisionPackage = aiDecisionPackageFactory.fromBatch(
                batch,
                currentUserTimezone(),
                readBatchPayloadString(batch, "scopeStart"),
                readBatchPayloadString(batch, "scopeEnd"),
                readRequestKind(batch)
        );
        List<SuggestionPreviewItemResponse> previewItems = buildPreviewItems(batch);
        int executableCommandCount = (int) batch.commands().stream()
                .filter(StructuredAiCommand::requiresConfirmation)
                .count();
        SuggestionExecutionSummaryResponse executionSummary = buildExecutionSummary(suggestion.getExecutionSnapshot());
        return new RescheduleSuggestionResponse(
                suggestion.getId().toString(),
                suggestion.getTriggerType().wireValue(),
                suggestion.getStatus().wireValue(),
                statusLabel(suggestion.getStatus()),
                statusDetail(suggestion, executionSummary),
                suggestion.getSummary(),
                suggestion.getReason(),
                originalRequest(suggestion),
                suggestion.getDecisionReason(),
                suggestion.getExplanation(),
                batch,
                decisionPackage,
                previewItems,
                executableCommandCount,
                executableCommandCount > 0,
                executionSummary,
                suggestion.getCreatedAt(),
                suggestion.getAppliedAt(),
                suggestion.getRejectedAt(),
                suggestion.getRevertedAt()
        );
    }

    private SuggestionExecutionSummaryResponse buildExecutionSummary(String payload) {
        List<AppliedCommandSnapshot> snapshots = readSnapshots(payload);
        if (snapshots.isEmpty()) {
            return null;
        }

        return summarizeSnapshots(snapshots);
    }

    private SuggestionExecutionSummaryResponse summarizeSnapshots(List<AppliedCommandSnapshot> snapshots) {
        int appliedCount = (int) snapshots.stream()
                .filter(snapshot -> "applied".equals(snapshot.outcome()))
                .count();
        int noOpCount = (int) snapshots.stream()
                .filter(snapshot -> "no_op".equals(snapshot.outcome()))
                .count();
        int providerWriteBlockedCount = (int) snapshots.stream()
                .filter(this::hasBlockedProviderWrite)
                .count();
        String detail = appliedCount == 1
                ? "일정표에 반영했습니다."
                : "%d개 변경을 일정표에 반영했습니다.".formatted(appliedCount);
        if (noOpCount > 0) {
            detail += " 처리하지 않은 항목 %d개는 그대로 두었습니다.".formatted(noOpCount);
        }
        if (providerWriteBlockedCount > 0) {
            detail += " Google 반영 대기 %d개가 있습니다.".formatted(providerWriteBlockedCount);
        }

        return new SuggestionExecutionSummaryResponse(
                snapshots.size(),
                appliedCount,
                noOpCount,
                detail
        );
    }

    private boolean hasBlockedProviderWrite(AppliedCommandSnapshot snapshot) {
        if (snapshot.providerWriteResult() != null) {
            return blocksExternalWrite(snapshot.providerWriteResult());
        }
        return snapshot.detail() != null
                && (snapshot.detail().contains(PROVIDER_WRITE_RECONNECT_REQUIRED_DETAIL)
                || snapshot.detail().contains(PROVIDER_WRITE_NO_CONNECTION_DETAIL));
    }

    private String statusLabel(RescheduleSuggestionStatus status) {
        return switch (status) {
            case PENDING -> "검토 대기";
            case APPLIED -> "적용 완료";
            case REJECTED -> "사용 안 함";
            case REVERTED -> "되돌림 완료";
        };
    }

    private String statusDetail(
            RescheduleSuggestion suggestion,
            SuggestionExecutionSummaryResponse executionSummary
    ) {
        return switch (suggestion.getStatus()) {
            case PENDING -> "확인 후 일정표에 반영하거나 닫을 수 있습니다.";
            case APPLIED -> executionSummary == null
                    ? "일정표에 반영했습니다."
                    : executionSummary.detail();
            case REJECTED -> suggestion.getDecisionReason() == null || suggestion.getDecisionReason().isBlank()
                    ? "요청을 닫았습니다."
                    : suggestion.getDecisionReason();
            case REVERTED -> suggestion.getDecisionReason() == null || suggestion.getDecisionReason().isBlank()
                    ? "적용한 제안을 되돌렸습니다."
                    : suggestion.getDecisionReason();
        };
    }



    private StructuredAiCommandBatch enrichBatchWithDecisionMetadata(
            StructuredAiCommandBatch batch,
            String timezone,
            String scopeStart,
            String scopeEnd
    ) {
        if (batch == null || batch.commands() == null || batch.commands().isEmpty()) {
            return batch;
        }
        String requestKind = readRequestKind(batch);
        String resolvedScopeStart = firstNonBlank(scopeStart, deriveScopeStart(batch));
        String resolvedScopeEnd = firstNonBlank(scopeEnd, deriveScopeEnd(batch));
        return new StructuredAiCommandBatch(
                batch.summary(),
                batch.explanation(),
                batch.commands().stream()
                        .map(command -> enrichCommandWithDecisionMetadata(command, timezone, resolvedScopeStart, resolvedScopeEnd, requestKind))
                        .toList()
        );
    }

    private StructuredAiCommand enrichCommandWithDecisionMetadata(
            StructuredAiCommand command,
            String timezone,
            String scopeStart,
            String scopeEnd,
            String requestKind
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(command.payload() == null ? Map.of() : command.payload());
        putIfPresent(payload, "timezone", timezone);
        putIfPresent(payload, "scopeStart", scopeStart);
        putIfPresent(payload, "scopeEnd", scopeEnd);
        putIfPresent(payload, "requestKind", requestKind);
        return new StructuredAiCommand(
                command.actionType(),
                command.targetType(),
                command.targetId(),
                Map.copyOf(payload),
                command.reason(),
                command.requiresConfirmation()
        );
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank() && !payload.containsKey(key)) {
            payload.put(key, value.trim());
        }
    }

    private String deriveScopeStart(StructuredAiCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> readString(command.payload(), "scopeStart", "startAt", "start_at"))
                .filter(this::isValidInstantText)
                .min(String::compareTo)
                .orElse(null);
    }

    private String deriveScopeEnd(StructuredAiCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> readString(command.payload(), "scopeEnd", "endAt", "end_at"))
                .filter(this::isValidInstantText)
                .max(String::compareTo)
                .orElse(null);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private boolean isValidInstantText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Instant.parse(value.trim());
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private String currentUserTimezone() {
        try {
            AppUser user = currentUserProvider.getCurrentUser();
            return user == null ? null : user.getTimezone();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String readBatchPayloadString(StructuredAiCommandBatch batch, String key) {
        if (batch == null || batch.commands() == null || batch.commands().isEmpty()) {
            return null;
        }
        return batch.commands().stream()
                .map(command -> readString(command.payload(), key))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
    private String readRequestKind(StructuredAiCommandBatch batch) {
        if (batch == null || batch.commands() == null) {
            return "manual_request";
        }
        return batch.commands().stream()
                .map(command -> readString(command.payload(), "requestKind"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("manual_request");
    }

    private List<SuggestionPreviewItemResponse> buildPreviewItems(StructuredAiCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> new SuggestionPreviewItemResponse(
                        command.actionType(),
                        command.targetType(),
                        command.targetId(),
                        previewTitle(command),
                        previewDetail(command),
                        command.reason(),
                        command.requiresConfirmation()
                ))
                .toList();
    }

    private String previewTitle(StructuredAiCommand command) {
        String explicit = readString(command.payload(), "activity", "title", "summary");
        if (explicit != null) {
            return explicit;
        }

        return switch (AgentCommandActionType.from(command.actionType())) {
            case MOVE_EVENT -> "일정 이동";
            case UPDATE_EVENT -> "일정 수정";
            case CREATE_EVENT -> "새 일정 생성";
            case DELETE_EVENT -> "일정 삭제";
            case REQUEST_RESCHEDULE -> "재조율 요청";
            case RUN_SYNC -> "동기화 요청";
            case PROPOSE_PRIORITY -> "우선순위 제안";
            case UPDATE_GOAL_PROGRESS -> "목표 진행 업데이트";
            case CHANGE_SETTINGS -> "설정 변경";
            case RECOMMEND_TASK -> "추천 할 일";
            case EXPLAIN_ONLY -> "설명";
            case REVERT_SUGGESTION -> "제안 되돌리기";
        };
    }

    private String previewDetail(StructuredAiCommand command) {
        String dayOfWeek = readString(command.payload(), "dayOfWeek", "day_of_week");
        String startTime = readString(command.payload(), "startTime", "start_time");
        String endTime = readString(command.payload(), "endTime", "end_time");
        String shiftMinutes = readString(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");

        if (dayOfWeek != null && startTime != null && endTime != null) {
            return "%s %s-%s".formatted(dayOfWeek, startTime, endTime);
        }
        if (startTime != null && endTime != null) {
            return "%s-%s".formatted(startTime, endTime);
        }
        if (shiftMinutes != null) {
            return "%s분 이동".formatted(shiftMinutes);
        }
        return null;
    }

    private StructuredAiCommandBatch readBatch(String payload) {
        try {
            return objectMapper.readValue(payload, StructuredAiCommandBatch.class);
        } catch (IOException exception) {
            throw new IllegalStateException("suggestion payload를 읽을 수 없습니다.", exception);
        }
    }

    private List<AppliedCommandSnapshot> readSnapshots(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new IllegalStateException("execution snapshot을 읽을 수 없습니다.", exception);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new IllegalStateException("payload를 저장할 수 없습니다.", exception);
        }
    }

    private AppliedCommandSnapshot applyCommand(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        AgentCommandActionType actionType = AgentCommandActionType.from(command.actionType());
        AgentCommandTargetType targetType = AgentCommandTargetType.from(command.targetType());

        if (targetType == AgentCommandTargetType.TASK || actionType == AgentCommandActionType.RECOMMEND_TASK) {
            return applyTaskCommand(userId, userZone, command, actionType);
        }

        if (targetType == AgentCommandTargetType.EVENT && command.targetId() != null && !command.targetId().isBlank()) {
            Optional<Event> event = eventRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId);
            if (event.isPresent()) {
                return switch (actionType) {
                    case MOVE_EVENT -> moveEvent(event.get(), userZone, command);
                    case UPDATE_EVENT -> updateEvent(event.get(), userZone, command);
                    case DELETE_EVENT -> deleteEvent(event.get(), command);
                    default -> noExecutableTarget(command, "해당 일정에 적용할 수 없는 요청입니다.");
                };
            }
        }

        if (targetType == AgentCommandTargetType.EVENT
                && actionType == AgentCommandActionType.CREATE_EVENT
                && readInstant(userZone, command.payload(), "startAt", "start_at") != null
                && readInstant(userZone, command.payload(), "endAt", "end_at") != null) {
            return createEvent(userId, userZone, command);
        }

        return switch (actionType) {
            case MOVE_EVENT -> moveScheduleBlock(userId, command);
            case UPDATE_EVENT -> updateScheduleBlock(userId, command);
            case CREATE_EVENT -> createScheduleBlock(userId, command);
            case DELETE_EVENT -> deleteScheduleBlock(userId, command);
            default -> new AppliedCommandSnapshot(
                    command.actionType(),
                    command.targetId(),
                    "no_op",
                    null,
                    null,
                    "실행 가능한 명령이 아니어서 상태만 기록했습니다."
            );
        };
    }

    private AppliedCommandSnapshot applyCommandOrAbort(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        try {
            return applyCommand(userId, userZone, command);
        } catch (DateTimeException | IllegalArgumentException | IllegalStateException exception) {
            String detail = exception instanceof DateTimeParseException
                    ? "AI가 돌려준 시간 형식을 해석하지 못했습니다. 예: 2026-05-16T19:10 또는 2026-05-16T10:10:00Z"
                    : exception.getMessage();
            throw new UserActionRequiredException(
                    "일부 변경을 적용할 수 없어 전체 적용을 중단했습니다: " + detail
            );
        }
    }

    private AppliedCommandSnapshot applyTaskCommand(
            UUID userId,
            ZoneId userZone,
            StructuredAiCommand command,
            AgentCommandActionType actionType
    ) {
        return switch (actionType) {
            case CREATE_EVENT, RECOMMEND_TASK -> createTask(userId, userZone, command);
            case MOVE_EVENT -> moveTask(userId, userZone, command);
            case UPDATE_EVENT -> updateTask(userId, userZone, command);
            case DELETE_EVENT -> deleteTask(userId, command);
            default -> noExecutableTarget(command, "해당 할 일에 적용할 수 없는 요청입니다.");
        };
    }

    private AppliedCommandSnapshot createEvent(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        Event event = new Event();
        event.setUserId(userId);
        event.setSourceType(EventSourceType.AGENT_GENERATED);
        applyEventPayload(event, command.payload(), true, userZone);
        Event saved = eventRepository.save(event);
        EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(saved, ProviderWriteOperation.CREATE);
        Event persisted = eventRepository.save(saved);
        return appliedProviderWriteSnapshot(
                command,
                persisted.getId().toString(),
                "새 일정을 추가했습니다.",
                null,
                snapshotEventRollback(persisted),
                null,
                null,
                enqueueResult
        );
    }

    private AppliedCommandSnapshot moveEvent(Event event, ZoneId userZone, StructuredAiCommand command) {
        EventRollbackState beforeState = snapshotEventRollback(event);
        try {
            Long shiftMinutes = readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");
            Instant explicitStartAt = readInstant(userZone, command.payload(), "startAt", "start_at");
            Instant explicitEndAt = readInstant(userZone, command.payload(), "endAt", "end_at");
            if (shiftMinutes != null) {
                event.setStartAt(event.getStartAt().plusSeconds(shiftMinutes * 60L));
                event.setEndAt(event.getEndAt().plusSeconds(shiftMinutes * 60L));
            }
            if (explicitStartAt != null) {
                event.setStartAt(explicitStartAt);
            }
            if (explicitEndAt != null) {
                event.setEndAt(explicitEndAt);
            }
            if (shiftMinutes == null && explicitStartAt == null && explicitEndAt == null) {
                return noExecutableTarget(command, "이동할 시간 정보가 payload에 없습니다.");
            }
            validateEventRange(event);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
            eventRepository.save(event);
            return appliedProviderWriteSnapshot(
                    command,
                    event.getId().toString(),
                    "일정 시간을 옮겼습니다.",
                    beforeState,
                    snapshotEventRollback(event),
                    null,
                    null,
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreEventFields(event, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot updateEvent(Event event, ZoneId userZone, StructuredAiCommand command) {
        EventRollbackState beforeState = snapshotEventRollback(event);
        try {
            applyEventPayload(event, command.payload(), false, userZone);
            validateEventRange(event);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
            eventRepository.save(event);
            return appliedProviderWriteSnapshot(
                    command,
                    event.getId().toString(),
                    "일정 내용을 수정했습니다.",
                    beforeState,
                    snapshotEventRollback(event),
                    null,
                    null,
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreEventFields(event, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot deleteEvent(Event event, StructuredAiCommand command) {
        if (isExternalBackedEvent(event)) {
            return noExecutableTarget(
                    command,
                    "외부 원본 일정은 AI 제안 적용으로 직접 삭제하지 않습니다. Google 캘린더에서 직접 확인하거나 수동 처리해 주세요."
            );
        }
        EventRollbackState beforeState = snapshotEventRollback(event);
        try {
            event.setStatus(EventStatus.CANCELLED);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.DELETE);
            eventRepository.save(event);
            return appliedProviderWriteSnapshot(
                    command,
                    event.getId().toString(),
                    "일정을 취소했습니다.",
                    beforeState,
                    snapshotEventRollback(event),
                    null,
                    null,
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreEventFields(event, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot createTask(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        Task task = new Task();
        task.setUserId(userId);
        task.setSourceType(TaskSourceType.AGENT_GENERATED);
        applyTaskPayload(task, command.payload(), true, userZone);
        Task saved = taskRepository.save(task);
        EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(saved, ProviderWriteOperation.CREATE);
        Task persisted = taskRepository.save(saved);
        return appliedProviderWriteSnapshot(
                command,
                persisted.getId().toString(),
                "할 일을 추가했습니다.",
                null,
                null,
                null,
                snapshotTaskRollback(persisted),
                enqueueResult
        );
    }

    private AppliedCommandSnapshot moveTask(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        Long shiftMinutes = readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");
        Instant dueDate = readInstant(userZone, command.payload(), "dueDate", "due_date");
        TaskRollbackState beforeState = snapshotTaskRollback(task);
        try {
            if (dueDate != null) {
                task.setDueDate(dueDate);
            } else if (shiftMinutes != null && task.getDueDate() != null) {
                task.setDueDate(task.getDueDate().plusSeconds(shiftMinutes * 60L));
            } else {
                return noExecutableTarget(command, "할 일 이동을 위한 dueDate 또는 기존 dueDate와 이동 시간이 필요합니다.");
            }
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
            taskRepository.save(task);
            return appliedProviderWriteSnapshot(
                    command,
                    task.getId().toString(),
                    "할 일 마감을 조정했습니다.",
                    null,
                    null,
                    beforeState,
                    snapshotTaskRollback(task),
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreTaskFields(task, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot updateTask(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        TaskRollbackState beforeState = snapshotTaskRollback(task);
        try {
            applyTaskPayload(task, command.payload(), false, userZone);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
            taskRepository.save(task);
            return appliedProviderWriteSnapshot(
                    command,
                    task.getId().toString(),
                    "할 일을 수정했습니다.",
                    null,
                    null,
                    beforeState,
                    snapshotTaskRollback(task),
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreTaskFields(task, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot deleteTask(UUID userId, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        if (isExternalBackedTask(task)) {
            return noExecutableTarget(
                    command,
                    "외부 원본 할 일은 AI 제안 적용으로 직접 삭제하지 않습니다. Google Tasks에서 직접 확인하거나 수동 처리해 주세요."
            );
        }
        TaskRollbackState beforeState = snapshotTaskRollback(task);
        try {
            task.setStatus(TaskStatus.CANCELLED);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.DELETE);
            taskRepository.save(task);
            return appliedProviderWriteSnapshot(
                    command,
                    task.getId().toString(),
                    "할 일을 취소했습니다.",
                    null,
                    null,
                    beforeState,
                    snapshotTaskRollback(task),
                    enqueueResult
            );
        } catch (RuntimeException exception) {
            restoreTaskFields(task, beforeState);
            throw exception;
        }
    }

    private AppliedCommandSnapshot appliedProviderWriteSnapshot(
            StructuredAiCommand command,
            String targetId,
            String canonicalDetail,
            EventRollbackState beforeEventState,
            EventRollbackState afterEventState,
            TaskRollbackState beforeTaskState,
            TaskRollbackState afterTaskState,
            EnqueueResult enqueueResult
    ) {
        return new AppliedCommandSnapshot(
                command.actionType(),
                targetId,
                "applied",
                null,
                null,
                canonicalDetail + " " + providerWriteDetail(enqueueResult),
                beforeEventState,
                afterEventState,
                beforeTaskState,
                afterTaskState,
                enqueueResult
        );
    }

    private String providerWriteDetail(EnqueueResult result) {
        return switch (result) {
            case ENQUEUED -> "Google 쓰기를 예약했습니다.";
            case WRITE_SCOPE_REQUIRED -> PROVIDER_WRITE_RECONNECT_REQUIRED_DETAIL;
            case NO_CONNECTION -> PROVIDER_WRITE_NO_CONNECTION_DETAIL;
            case NOOP -> "외부 대상이 없어 Google 쓰기는 생략했습니다.";
        };
    }

    private boolean blocksExternalWrite(EnqueueResult result) {
        return result == EnqueueResult.WRITE_SCOPE_REQUIRED || result == EnqueueResult.NO_CONNECTION;
    }

    private boolean isExternalBackedEvent(Event event) {
        return event.getSourceType() == EventSourceType.GOOGLE_CALENDAR
                || event.getExternalSourceId() != null
                || event.getSyncState() == com.timetable.operator.common.domain.PlannerSyncState.IMPORTED
                || event.getSyncState() == com.timetable.operator.common.domain.PlannerSyncState.SYNCED;
    }

    private boolean isExternalBackedTask(Task task) {
        return task.getSourceType() == TaskSourceType.GOOGLE_TASKS
                || task.getExternalSourceId() != null
                || task.getSyncState() == com.timetable.operator.common.domain.PlannerSyncState.IMPORTED
                || task.getSyncState() == com.timetable.operator.common.domain.PlannerSyncState.SYNCED;
    }

    private Task getOwnedTask(UUID userId, StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            throw new IllegalArgumentException("할 일 target_id가 필요합니다.");
        }
        return taskRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId)
                .orElseThrow(() -> new IllegalArgumentException("대상 할 일를 찾을 수 없습니다."));
    }

    private AppliedCommandSnapshot moveScheduleBlock(UUID userId, StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            return noExecutableTarget(command, "target_id가 없어 move_event를 적용할 수 없습니다.");
        }
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId)
                .orElseThrow(() -> new IllegalArgumentException("이동할 일정 블록을 찾을 수 없습니다."));
        ScheduleBlockSnapshot beforeState = snapshot(block);

        Long shiftMinutes = readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");
        String explicitDayOfWeek = readString(command.payload(), "dayOfWeek", "day_of_week");
        String explicitStartTime = readString(command.payload(), "startTime", "start_time");
        String explicitEndTime = readString(command.payload(), "endTime", "end_time");

        if (shiftMinutes == null && explicitDayOfWeek == null && explicitStartTime == null && explicitEndTime == null) {
            return noExecutableTarget(command, "이동할 시간 정보가 payload에 없습니다.");
        }

        try {
            if (shiftMinutes != null) {
                ScheduleBlockRules.ShiftedScheduleBlock shifted = scheduleBlockRules.shift(
                        block.getDayOfWeek(),
                        block.getStartTime(),
                        block.getEndTime(),
                        shiftMinutes
                );
                block.setDayOfWeek(shifted.dayOfWeek());
                block.setStartTime(shifted.startTime());
                block.setEndTime(shifted.endTime());
            }
            if (explicitDayOfWeek != null) {
                block.setDayOfWeek(DayOfWeek.valueOf(explicitDayOfWeek.toUpperCase()));
            }
            if (explicitStartTime != null) {
                block.setStartTime(LocalTime.parse(explicitStartTime));
            }
            if (explicitEndTime != null) {
                block.setEndTime(LocalTime.parse(explicitEndTime));
            }
            scheduleBlockRules.validateForUser(userId, block);
        } catch (RuntimeException exception) {
            restoreScheduleBlockFields(block, beforeState);
            throw exception;
        }
        ScheduleBlock saved = scheduleBlockRepository.save(block);
        return new AppliedCommandSnapshot(
                command.actionType(),
                command.targetId(),
                "applied",
                beforeState,
                snapshot(saved),
                "주간 루틴 시간을 옮겼습니다."
        );
    }

    private AppliedCommandSnapshot updateScheduleBlock(UUID userId, StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            return noExecutableTarget(command, "target_id가 없어 update_event를 적용할 수 없습니다.");
        }
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId)
                .orElseThrow(() -> new IllegalArgumentException("수정할 일정 블록을 찾을 수 없습니다."));
        ScheduleBlockSnapshot beforeState = snapshot(block);

        try {
            applyScheduleBlockPayload(block, command.payload(), false);
            scheduleBlockRules.validateForUser(userId, block);
        } catch (RuntimeException exception) {
            restoreScheduleBlockFields(block, beforeState);
            throw exception;
        }
        ScheduleBlock saved = scheduleBlockRepository.save(block);
        return new AppliedCommandSnapshot(
                command.actionType(),
                command.targetId(),
                "applied",
                beforeState,
                snapshot(saved),
                "주간 루틴을 수정했습니다."
        );
    }

    private AppliedCommandSnapshot createScheduleBlock(UUID userId, StructuredAiCommand command) {
        ScheduleBlock block = new ScheduleBlock();
        block.setUserId(userId);
        applyScheduleBlockPayload(block, command.payload(), true);
        block.setSourceType(ScheduleSourceType.MANUAL);
        block.setSourceRef("agent-suggestion");
        scheduleBlockRules.validateForUser(userId, block);
        ScheduleBlock saved = scheduleBlockRepository.save(block);
        return new AppliedCommandSnapshot(
                command.actionType(),
                saved.getId().toString(),
                "applied",
                null,
                snapshot(saved),
                "주간 루틴을 추가했습니다."
        );
    }

    private AppliedCommandSnapshot deleteScheduleBlock(UUID userId, StructuredAiCommand command) {
        if (command.targetId() == null || command.targetId().isBlank()) {
            return noExecutableTarget(command, "target_id가 없어 delete_event를 적용할 수 없습니다.");
        }
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId)
                .orElseThrow(() -> new IllegalArgumentException("삭제할 일정 블록을 찾을 수 없습니다."));
        ScheduleBlockSnapshot beforeState = snapshot(block);
        scheduleBlockRepository.delete(block);
        return new AppliedCommandSnapshot(
                command.actionType(),
                command.targetId(),
                "applied",
                beforeState,
                null,
                "주간 루틴을 삭제했습니다."
        );
    }

    private AppliedCommandSnapshot noExecutableTarget(StructuredAiCommand command, String detail) {
        return new AppliedCommandSnapshot(
                command.actionType(),
                command.targetId(),
                "no_op",
                null,
                null,
                detail
        );
    }

    private void revertSnapshot(UUID userId, AppliedCommandSnapshot snapshot) {
        if ("applied".equals(snapshot.outcome())
                && snapshot.beforeState() == null
                && snapshot.afterState() == null
                && snapshot.beforeEventState() == null
                && snapshot.afterEventState() == null
                && snapshot.beforeTaskState() == null
                && snapshot.afterTaskState() == null) {
            throw new UserActionRequiredException("이 변경은 자동 되돌리기를 지원하지 않습니다. 일정 화면에서 직접 확인해 주세요.");
        }
        AgentCommandActionType actionType = AgentCommandActionType.from(snapshot.actionType());
        switch (actionType) {
            case CREATE_EVENT -> {
                if (snapshot.afterState() != null) {
                    scheduleBlockRepository.findByIdAndUserId(UUID.fromString(snapshot.afterState().id()), userId)
                            .ifPresent(scheduleBlockRepository::delete);
                }
                if (snapshot.afterEventState() != null) {
                    cancelCanonicalEvent(userId, snapshot.afterEventState());
                }
                if (snapshot.afterTaskState() != null) {
                    cancelCanonicalTask(userId, snapshot.afterTaskState());
                }
            }
            case MOVE_EVENT, UPDATE_EVENT, DELETE_EVENT -> {
                if (snapshot.beforeState() != null) {
                    restoreSnapshot(userId, snapshot.beforeState());
                }
                if (snapshot.beforeEventState() != null) {
                    restoreEventSnapshot(userId, snapshot.beforeEventState());
                }
                if (snapshot.beforeTaskState() != null) {
                    restoreTaskSnapshot(userId, snapshot.beforeTaskState());
                }
            }
            default -> {
            }
        }
    }

    private void cancelCanonicalEvent(UUID userId, EventRollbackState snapshot) {
        eventRepository.findByIdAndUserId(UUID.fromString(snapshot.id()), userId).ifPresent(event -> {
            event.setStatus(EventStatus.CANCELLED);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.DELETE);
            eventRepository.save(event);
            log.debug("Reverted canonical event create by cancelling event {} ({})", snapshot.id(), enqueueResult);
        });
    }

    private void cancelCanonicalTask(UUID userId, TaskRollbackState snapshot) {
        taskRepository.findByIdAndUserId(UUID.fromString(snapshot.id()), userId).ifPresent(task -> {
            task.setStatus(TaskStatus.CANCELLED);
            EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.DELETE);
            taskRepository.save(task);
            log.debug("Reverted canonical task create by cancelling task {} ({})", snapshot.id(), enqueueResult);
        });
    }

    private void restoreEventSnapshot(UUID userId, EventRollbackState snapshot) {
        Event event = eventRepository.findByIdAndUserId(UUID.fromString(snapshot.id()), userId)
                .orElseGet(Event::new);
        event.setId(UUID.fromString(snapshot.id()));
        event.setUserId(userId);
        restoreEventFields(event, snapshot);
        EnqueueResult enqueueResult = providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
        eventRepository.save(event);
        log.debug("Reverted canonical event {} and queued provider update ({})", snapshot.id(), enqueueResult);
    }

    private void restoreTaskSnapshot(UUID userId, TaskRollbackState snapshot) {
        Task task = taskRepository.findByIdAndUserId(UUID.fromString(snapshot.id()), userId)
                .orElseGet(Task::new);
        task.setId(UUID.fromString(snapshot.id()));
        task.setUserId(userId);
        restoreTaskFields(task, snapshot);
        EnqueueResult enqueueResult = providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
        taskRepository.save(task);
        log.debug("Reverted canonical task {} and queued provider update ({})", snapshot.id(), enqueueResult);
    }

    private void restoreSnapshot(UUID userId, ScheduleBlockSnapshot snapshot) {
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(UUID.fromString(snapshot.id()), userId)
                .orElseGet(ScheduleBlock::new);
        block.setId(UUID.fromString(snapshot.id()));
        block.setUserId(userId);
        block.setDayOfWeek(DayOfWeek.valueOf(snapshot.dayOfWeek()));
        block.setStartTime(LocalTime.parse(snapshot.startTime()));
        block.setEndTime(LocalTime.parse(snapshot.endTime()));
        block.setActivity(snapshot.activity());
        block.setCategory(ScheduleCategory.valueOf(snapshot.category()));
        block.setNote(snapshot.note());
        block.setSourceType(ScheduleSourceType.valueOf(snapshot.sourceType()));
        block.setSourceRef(snapshot.sourceRef());
        scheduleBlockRepository.save(block);
    }

    private void restoreScheduleBlockFields(ScheduleBlock block, ScheduleBlockSnapshot snapshot) {
        block.setDayOfWeek(DayOfWeek.valueOf(snapshot.dayOfWeek()));
        block.setStartTime(LocalTime.parse(snapshot.startTime()));
        block.setEndTime(LocalTime.parse(snapshot.endTime()));
        block.setActivity(snapshot.activity());
        block.setCategory(ScheduleCategory.valueOf(snapshot.category()));
        block.setNote(snapshot.note());
        block.setSourceType(ScheduleSourceType.valueOf(snapshot.sourceType()));
        block.setSourceRef(snapshot.sourceRef());
    }

    private ScheduleBlockSnapshot snapshot(ScheduleBlock block) {
        return new ScheduleBlockSnapshot(
                block.getId().toString(),
                block.getDayOfWeek().name(),
                block.getStartTime().toString(),
                block.getEndTime().toString(),
                block.getActivity(),
                block.getCategory().name(),
                block.getNote(),
                block.getSourceType().name(),
                block.getSourceRef()
        );
    }

    private void applyScheduleBlockPayload(ScheduleBlock block, Map<String, Object> payload, boolean requireAllFields) {
        String dayOfWeek = readString(payload, "dayOfWeek", "day_of_week");
        String startTime = readString(payload, "startTime", "start_time");
        String endTime = readString(payload, "endTime", "end_time");
        String activity = readString(payload, "activity", "title", "summary");
        String category = readString(payload, "category");
        String note = readString(payload, "note", "description");

        if (requireAllFields && (dayOfWeek == null || startTime == null || endTime == null || activity == null)) {
            throw new IllegalArgumentException("create_event payload에는 dayOfWeek, startTime, endTime, activity가 필요합니다.");
        }

        if (dayOfWeek != null) {
            block.setDayOfWeek(DayOfWeek.valueOf(dayOfWeek.toUpperCase()));
        }
        if (startTime != null) {
            block.setStartTime(LocalTime.parse(startTime));
        }
        if (endTime != null) {
            block.setEndTime(LocalTime.parse(endTime));
        }
        if (activity != null) {
            block.setActivity(clampCanonicalTitle(activity));
        }
        if (category != null) {
            block.setCategory(ScheduleCategory.valueOf(category.toUpperCase()));
        } else if (requireAllFields) {
            block.setCategory(ScheduleCategory.LIFE);
        }
        if (note != null || requireAllFields) {
            block.setNote(ScheduleNoteSanitizer.cleanGeneratedNote(note));
        }
    }

    private void applyEventPayload(Event event, Map<String, Object> payload, boolean requireTimeRange, ZoneId userZone) {
        String title = readString(payload, "title", "activity", "summary");
        String description = readString(payload, "description", "note");
        Instant startAt = readInstant(userZone, payload, "startAt", "start_at");
        Instant endAt = readInstant(userZone, payload, "endAt", "end_at");
        String category = readString(payload, "category");
        String priority = readString(payload, "priority");
        String goalId = readString(payload, "goalId", "goal_id");

        if (requireTimeRange && (startAt == null || endAt == null)) {
            throw new IllegalArgumentException("일정을 추가하려면 시작 시간과 종료 시간이 필요합니다.");
        }
        if (title != null) {
            event.setTitle(clampCanonicalTitle(title));
        } else if (requireTimeRange) {
            event.setTitle("AI 제안 일정");
        }
        if (payload != null && (payload.containsKey("description") || payload.containsKey("note"))) {
            event.setDescription(blankToNull(description));
        }
        if (startAt != null) {
            event.setStartAt(startAt);
        }
        if (endAt != null) {
            event.setEndAt(endAt);
        }
        if (category != null) {
            event.setCategory(ScheduleCategory.valueOf(category.toUpperCase()));
        }
        if (priority != null) {
            event.setPriority(readPriority(priority));
        }
        if (goalId != null) {
            event.setGoalId(UUID.fromString(goalId));
        }
        if (event.getStatus() == null) {
            event.setStatus(EventStatus.PLANNED);
        }
        validateEventRange(event);
    }

    private void validateEventRange(Event event) {
        if (event.getStartAt() == null || event.getEndAt() == null || !event.getEndAt().isAfter(event.getStartAt())) {
            throw new IllegalArgumentException("이벤트 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
    }

    private void applyTaskPayload(Task task, Map<String, Object> payload, boolean requireTitle, ZoneId userZone) {
        String title = readString(payload, "title", "activity", "summary");
        String description = readString(payload, "description", "note");
        Instant dueDate = readInstant(userZone, payload, "dueDate", "due_date");
        String estimatedMinutes = readString(payload, "estimatedMinutes", "estimated_minutes");
        String priority = readString(payload, "priority");
        String goalId = readString(payload, "goalId", "goal_id");
        String category = readString(payload, "category");

        if (title != null) {
            task.setTitle(clampCanonicalTitle(title));
        } else if (requireTitle) {
            task.setTitle("AI 추천 할 일");
        }
        if (payload != null && (payload.containsKey("description") || payload.containsKey("note"))) {
            task.setDescription(blankToNull(description));
        }
        if (dueDate != null) {
            task.setDueDate(dueDate);
        }
        if (estimatedMinutes != null) {
            task.setEstimatedMinutes(readEstimatedMinutes(estimatedMinutes));
        }
        if (priority != null) {
            task.setPriority(readPriority(priority));
        }
        if (goalId != null) {
            task.setGoalId(UUID.fromString(goalId));
        }
        if (category != null) {
            task.setCategory(blankToNull(category));
        }
        if (task.getEstimatedMinutes() < 0) {
            throw new IllegalArgumentException("예상 소요 시간은 0 이상이어야 합니다.");
        }
        if (task.getStatus() == null) {
            task.setStatus(TaskStatus.TODO);
        }
    }

    private short readPriority(String value) {
        int priority = readInteger(value, "priority는 1부터 5 사이의 숫자여야 합니다.");
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("priority는 1부터 5 사이의 숫자여야 합니다.");
        }
        return (short) priority;
    }

    private int readEstimatedMinutes(String value) {
        int estimatedMinutes = readInteger(value, "estimatedMinutes는 0 이상의 정수여야 합니다.");
        if (estimatedMinutes < 0) {
            throw new IllegalArgumentException("estimatedMinutes는 0 이상의 정수여야 합니다.");
        }
        return estimatedMinutes;
    }

    private int readInteger(String value, String errorMessage) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(errorMessage, exception);
        }
    }


    private EventRollbackState snapshotEventRollback(Event event) {
        return new EventRollbackState(
                event.getId().toString(),
                event.getTitle(),
                event.getDescription(),
                event.getStartAt(),
                event.getEndAt(),
                event.getPriority(),
                event.getCategory(),
                event.getGoalId(),
                event.getStatus(),
                event.getSourceType(),
                event.getSyncState(),
                event.getExternalSourceId(),
                event.getExternalEtag(),
                event.getLastSyncedAt()
        );
    }

    private void restoreEventFields(Event event, EventRollbackState snapshot) {
        event.setTitle(snapshot.title());
        event.setDescription(snapshot.description());
        event.setStartAt(snapshot.startAt());
        event.setEndAt(snapshot.endAt());
        event.setPriority(snapshot.priority());
        event.setCategory(snapshot.category());
        event.setGoalId(snapshot.goalId());
        event.setStatus(snapshot.status());
        event.setSourceType(snapshot.sourceType());
        event.setSyncState(snapshot.syncState());
        event.setExternalSourceId(snapshot.externalSourceId());
        event.setExternalEtag(snapshot.externalEtag());
        event.setLastSyncedAt(snapshot.lastSyncedAt());
    }

    private TaskRollbackState snapshotTaskRollback(Task task) {
        return new TaskRollbackState(
                task.getId().toString(),
                task.getTitle(),
                task.getDescription(),
                task.getDueDate(),
                task.getEstimatedMinutes(),
                task.getPriority(),
                task.getGoalId(),
                task.getCategory(),
                task.getStatus(),
                task.getSourceType(),
                task.getSyncState(),
                task.getEventId(),
                task.getExternalSourceId(),
                task.getExternalEtag(),
                task.getLastSyncedAt(),
                task.getCompletedAt(),
                task.getActualMinutes()
        );
    }

    private void restoreTaskFields(Task task, TaskRollbackState snapshot) {
        task.setTitle(snapshot.title());
        task.setDescription(snapshot.description());
        task.setDueDate(snapshot.dueDate());
        task.setEstimatedMinutes(snapshot.estimatedMinutes());
        task.setPriority(snapshot.priority());
        task.setGoalId(snapshot.goalId());
        task.setCategory(snapshot.category());
        task.setStatus(snapshot.status());
        task.setSourceType(snapshot.sourceType());
        task.setSyncState(snapshot.syncState());
        task.setEventId(snapshot.eventId());
        task.setExternalSourceId(snapshot.externalSourceId());
        task.setExternalEtag(snapshot.externalEtag());
        task.setLastSyncedAt(snapshot.lastSyncedAt());
        task.setCompletedAt(snapshot.completedAt());
        task.setActualMinutes(snapshot.actualMinutes());
    }

    private AiRescheduleClient.RescheduleAiContext buildAiContext(AppUser user, ManualRescheduleRequest request) {
        ZoneId userZone = AiLocalDateTimeParser.resolveUserZone(user.getTimezone());
        PlanningRange planningRange = resolvePlanningRange(request, userZone);
        List<Event> events = eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                user.getId(),
                EventStatus.CANCELLED,
                planningRange.endAt(),
                planningRange.startAt()
        );
        List<ScheduleBlock> scheduleBlocks = scheduleBlockRepository.findByUserId(user.getId());
        List<AiRescheduleClient.MessageHistoryContext> messageHistory = buildMessageHistory(user.getId());
        List<AiRescheduleClient.AvailabilityWindowContext> availabilityWindows =
                shouldIncludeAvailabilityWindows(request)
                        ? buildAvailabilityWindows(scheduleBlocks, events, planningRange, userZone)
                        : List.of();

        AiRescheduleClient.RescheduleAiContext context = new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext(
                        user.getId().toString(),
                        blankToDefault(user.getTimezone(), "Asia/Seoul")
                ),
                new AiRescheduleClient.RequestContext(
                        request.triggerType(),
                        request.reason(),
                        request.targetRangeStart() == null ? null : request.targetRangeStart().toString(),
                        request.targetRangeEnd() == null ? null : request.targetRangeEnd().toString(),
                        planningRange.startAt().toString(),
                        planningRange.endAt().toString()
                ),
                scheduleBlocks.stream()
                        .limit(80)
                        .map(this::toAiScheduleBlock)
                        .toList(),
                events.stream()
                        .limit(120)
                        .map(this::toAiEvent)
                        .toList(),
                taskRepository.findByUserIdAndStatusInOrderByPriorityAscDueDateAsc(
                                user.getId(),
                                List.of(TaskStatus.TODO, TaskStatus.SCHEDULED, TaskStatus.IN_PROGRESS, TaskStatus.DEFERRED)
                        ).stream()
                        .limit(120)
                        .map(this::toAiTask)
                        .toList(),
                messageHistory,
                availabilityWindows
        );
        return new AiRescheduleClient.RescheduleAiContext(
                context.user(),
                context.request(),
                context.weeklyBlocks(),
                context.events(),
                context.tasks(),
                context.messageHistory(),
                context.availabilityWindows(),
                aiContextPackageBuilder.build(context)
        );
    }

    private PlanningRange resolvePlanningRange(ManualRescheduleRequest request, ZoneId userZone) {
        if (request.targetRangeStart() != null && request.targetRangeEnd() != null
                && request.targetRangeEnd().isAfter(request.targetRangeStart())) {
            return new PlanningRange(request.targetRangeStart(), request.targetRangeEnd());
        }

        ZonedDateTime currentMinute = ZonedDateTime.now(userZone).truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime endAfterFutureDays = currentMinute.toLocalDate()
                .plusDays(DEFAULT_AI_FUTURE_CALENDAR_DAYS + 1L)
                .atStartOfDay(userZone);
        return new PlanningRange(
                currentMinute.toInstant(),
                endAfterFutureDays.toInstant()
        );
    }

    private List<AiRescheduleClient.MessageHistoryContext> buildMessageHistory(UUID userId) {
        List<AiRescheduleClient.MessageHistoryContext> newestFirst = rescheduleSuggestionRepository
                .findByUserIdAndStatusInOrderByCreatedAtDesc(
                        userId,
                        List.of(
                                RescheduleSuggestionStatus.PENDING,
                                RescheduleSuggestionStatus.APPLIED,
                                RescheduleSuggestionStatus.REJECTED
                        ),
                        PageRequest.of(0, AI_MESSAGE_HISTORY_LIMIT)
                )
                .stream()
                .map(suggestion -> new AiRescheduleClient.MessageHistoryContext(
                        suggestion.getCreatedAt() == null ? null : suggestion.getCreatedAt().toString(),
                        suggestion.getStatus().wireValue(),
                        compactHistoryText(originalRequest(suggestion)),
                        compactHistoryText(suggestion.getSummary()),
                        historyAssistantExplanation(suggestion)
                ))
                .toList();
        List<AiRescheduleClient.MessageHistoryContext> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    private String originalRequest(RescheduleSuggestion suggestion) {
        if (suggestion.getOriginalRequest() != null && !suggestion.getOriginalRequest().isBlank()) {
            return suggestion.getOriginalRequest();
        }
        return suggestion.getReason();
    }

    private String historyAssistantExplanation(RescheduleSuggestion suggestion) {
        if (suggestion.getStatus() == RescheduleSuggestionStatus.REJECTED) {
            return compactHistoryText(suggestion.getDecisionReason());
        }
        return compactHistoryText(suggestion.getExplanation());
    }

    private String compactHistoryText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= AI_HISTORY_FIELD_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, AI_HISTORY_FIELD_MAX_CHARS);
    }

    private boolean shouldIncludeAvailabilityWindows(ManualRescheduleRequest request) {
        if (request.targetRangeStart() != null && request.targetRangeEnd() != null) {
            return true;
        }
        String normalized = (request.reason() == null ? "" : request.reason()).toLowerCase();
        if (normalized.matches(".*\\d{1,2}\\s*(시|:).*")) {
            return true;
        }
        return List.of(
                "추가", "시간", "언제", "빈", "비어", "가능", "가용", "넣", "잡", "배치",
                "오전", "오후", "내일", "오늘", "모레", "다음",
                "add", "time", "slot", "free", "available", "availability", "schedule"
        ).stream().anyMatch(normalized::contains);
    }

    private List<AiRescheduleClient.AvailabilityWindowContext> buildAvailabilityWindows(
            List<ScheduleBlock> scheduleBlocks,
            List<Event> events,
            PlanningRange planningRange,
            ZoneId userZone
    ) {
        List<BusyWindow> busyWindows = new ArrayList<>();
        scheduleBlocks.forEach(block -> busyWindows.addAll(scheduleBlockBusyWindows(block, planningRange, userZone)));
        events.forEach(event -> busyWindows.add(new BusyWindow(event.getStartAt(), event.getEndAt())));

        List<BusyWindow> mergedBusyWindows = busyWindows.stream()
                .map(window -> window.clip(planningRange))
                .filter(window -> window.endAt().isAfter(window.startAt()))
                .sorted(Comparator.comparing(BusyWindow::startAt))
                .toList();

        List<AiRescheduleClient.AvailabilityWindowContext> windows = new ArrayList<>();
        Instant cursor = planningRange.startAt();
        List<BusyWindow> mergedBusyWindowList = new ArrayList<>();
        mergedBusyWindows.forEach(window -> mergeBusyWindow(mergedBusyWindowList, window));
        for (BusyWindow busy : mergedBusyWindowList) {
            if (busy.startAt().isAfter(cursor)) {
                addAvailabilityWindow(windows, cursor, busy.startAt(), userZone);
                if (windows.size() >= MAX_AVAILABILITY_WINDOWS) {
                    return windows;
                }
            }
            if (busy.endAt().isAfter(cursor)) {
                cursor = busy.endAt();
            }
        }
        if (planningRange.endAt().isAfter(cursor)) {
            addAvailabilityWindow(windows, cursor, planningRange.endAt(), userZone);
        }
        return windows.size() > MAX_AVAILABILITY_WINDOWS ? windows.subList(0, MAX_AVAILABILITY_WINDOWS) : windows;
    }

    private List<BusyWindow> scheduleBlockBusyWindows(
            ScheduleBlock block,
            PlanningRange planningRange,
            ZoneId userZone
    ) {
        List<BusyWindow> windows = new ArrayList<>();
        LocalDate cursor = planningRange.startAt().atZone(userZone).toLocalDate();
        LocalDate endDate = planningRange.endAt().atZone(userZone).toLocalDate();
        while (!cursor.isAfter(endDate)) {
            if (cursor.getDayOfWeek() == block.getDayOfWeek()) {
                ZonedDateTime start = ZonedDateTime.of(cursor, block.getStartTime(), userZone);
                ZonedDateTime end = ZonedDateTime.of(cursor, block.getEndTime(), userZone);
                if (!block.getEndTime().isAfter(block.getStartTime())) {
                    end = end.plusDays(1);
                }
                windows.add(new BusyWindow(start.toInstant(), end.toInstant()));
            }
            cursor = cursor.plusDays(1);
        }
        return windows;
    }

    private void mergeBusyWindow(List<BusyWindow> merged, BusyWindow next) {
        if (merged.isEmpty()) {
            merged.add(next);
            return;
        }
        BusyWindow last = merged.getLast();
        if (next.startAt().isAfter(last.endAt())) {
            merged.add(next);
            return;
        }
        if (next.endAt().isAfter(last.endAt())) {
            merged.set(merged.size() - 1, new BusyWindow(last.startAt(), next.endAt()));
        }
    }

    private void addAvailabilityWindow(
            List<AiRescheduleClient.AvailabilityWindowContext> windows,
            Instant startAt,
            Instant endAt,
            ZoneId userZone
    ) {
        long minutes = ChronoUnit.MINUTES.between(startAt, endAt);
        if (minutes < MIN_AVAILABILITY_WINDOW_MINUTES) {
            return;
        }
        windows.add(new AiRescheduleClient.AvailabilityWindowContext(
                startAt.toString(),
                endAt.toString(),
                formatLocalAvailabilityLabel(startAt, endAt, userZone),
                minutes,
                "computed_empty_slot"
        ));
    }

    private String formatLocalAvailabilityLabel(Instant startAt, Instant endAt, ZoneId userZone) {
        ZonedDateTime start = startAt.atZone(userZone);
        ZonedDateTime end = endAt.atZone(userZone);
        String endLabel = start.toLocalDate().equals(end.toLocalDate())
                ? "%02d:%02d".formatted(end.getHour(), end.getMinute())
                : "%s %02d:%02d".formatted(end.toLocalDate(), end.getHour(), end.getMinute());
        return "%s %02d:%02d-%s".formatted(
                start.toLocalDate(),
                start.getHour(),
                start.getMinute(),
                endLabel
        );
    }

    private record PlanningRange(Instant startAt, Instant endAt) {
    }

    private record BusyWindow(Instant startAt, Instant endAt) {
        BusyWindow clip(PlanningRange range) {
            Instant clippedStart = startAt.isBefore(range.startAt()) ? range.startAt() : startAt;
            Instant clippedEnd = endAt.isAfter(range.endAt()) ? range.endAt() : endAt;
            return new BusyWindow(clippedStart, clippedEnd);
        }
    }

    private AiRescheduleClient.ScheduleBlockContext toAiScheduleBlock(ScheduleBlock block) {
        return new AiRescheduleClient.ScheduleBlockContext(
                block.getId().toString(),
                block.getDayOfWeek().name(),
                block.getStartTime().toString(),
                block.getEndTime().toString(),
                block.getActivity(),
                block.getCategory().name(),
                block.getNote()
        );
    }

    private AiRescheduleClient.EventContext toAiEvent(Event event) {
        return new AiRescheduleClient.EventContext(
                event.getId().toString(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory().name(),
                event.getStartAt().toString(),
                event.getEndAt().toString(),
                event.getStatus().name(),
                event.getSyncState().name()
        );
    }

    private AiRescheduleClient.TaskContext toAiTask(Task task) {
        return new AiRescheduleClient.TaskContext(
                task.getId().toString(),
                task.getTitle(),
                task.getDescription(),
                task.getCategory(),
                task.getDueDate() == null ? null : task.getDueDate().toString(),
                task.getEstimatedMinutes(),
                task.getActualMinutes(),
                task.getPriority(),
                task.getStatus().name(),
                task.getSyncState().name()
        );
    }

    private Instant readInstant(ZoneId userZone, Map<String, Object> payload, String... keys) {
        String value = readString(payload, keys);
        return value == null ? null : AiLocalDateTimeParser.parseRequired(value, userZone);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String clampCanonicalTitle(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.length() <= CANONICAL_TITLE_MAX_LENGTH) {
            return trimmed;
        }
        int endIndex = CANONICAL_TITLE_MAX_LENGTH;
        if (Character.isHighSurrogate(trimmed.charAt(endIndex - 1))) {
            endIndex--;
        }
        return trimmed.substring(0, endIndex);
    }

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private Long readLong(Map<String, Object> payload, String... keys) {
        String value = readString(payload, keys);
        return value == null ? null : Long.parseLong(value);
    }

    public record ManualRescheduleRequest(
            String triggerType,
            Instant targetRangeStart,
            Instant targetRangeEnd,
            @NotBlank String reason
    ) {
    }

    public record SuggestionDecisionRequest(
            String reason
    ) {
    }

    public record RescheduleSuggestionResponse(
            String id,
            String triggerType,
            String status,
            String statusLabel,
            String statusDetail,
            String summary,
            String reason,
            String originalRequest,
            String decisionReason,
            String explanation,
            StructuredAiCommandBatch commandBatch,
            AiDecisionPackage decisionPackage,
            List<SuggestionPreviewItemResponse> previewItems,
            int executableCommandCount,
            boolean executable,
            SuggestionExecutionSummaryResponse executionSummary,
            Instant createdAt,
            Instant appliedAt,
            Instant rejectedAt,
            Instant revertedAt
    ) {
    }

    public record SuggestionPreviewItemResponse(
            String actionType,
            String targetType,
            String targetId,
            String title,
            String detail,
            String reason,
            boolean executable
    ) {
    }

    public record SuggestionExecutionSummaryResponse(
            int totalCount,
            int appliedCount,
            int noOpCount,
            String detail
    ) {
    }

    private record AppliedCommandSnapshot(
            String actionType,
            String targetId,
            String outcome,
            ScheduleBlockSnapshot beforeState,
            ScheduleBlockSnapshot afterState,
            String detail,
            EventRollbackState beforeEventState,
            EventRollbackState afterEventState,
            TaskRollbackState beforeTaskState,
            TaskRollbackState afterTaskState,
            EnqueueResult providerWriteResult
    ) {

        private AppliedCommandSnapshot(
                String actionType,
                String targetId,
                String outcome,
                ScheduleBlockSnapshot beforeState,
                ScheduleBlockSnapshot afterState,
                String detail
        ) {
            this(actionType, targetId, outcome, beforeState, afterState, detail, null, null, null, null, null);
        }
    }

    private record ScheduleBlockSnapshot(
            String id,
            String dayOfWeek,
            String startTime,
            String endTime,
            String activity,
            String category,
            String note,
            String sourceType,
            String sourceRef
    ) {
    }

    private record EventRollbackState(
            String id,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            short priority,
            ScheduleCategory category,
            UUID goalId,
            EventStatus status,
            EventSourceType sourceType,
            com.timetable.operator.common.domain.PlannerSyncState syncState,
            String externalSourceId,
            String externalEtag,
            Instant lastSyncedAt
    ) {
    }

    private record TaskRollbackState(
            String id,
            String title,
            String description,
            Instant dueDate,
            int estimatedMinutes,
            short priority,
            UUID goalId,
            String category,
            TaskStatus status,
            TaskSourceType sourceType,
            com.timetable.operator.common.domain.PlannerSyncState syncState,
            UUID eventId,
            String externalSourceId,
            String externalEtag,
            Instant lastSyncedAt,
            Instant completedAt,
            Integer actualMinutes
    ) {
    }
}
