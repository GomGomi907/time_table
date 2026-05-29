package com.timetable.operator.agent.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.infrastructure.RescheduleSuggestionRepository;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.application.ScheduleBlockRules;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RescheduleSuggestionService {

    private static final ZoneId DEFAULT_AI_LOCAL_DATE_TIME_ZONE = ZoneId.of("Asia/Seoul");

    private final RescheduleSuggestionRepository rescheduleSuggestionRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final ScheduleBlockRules scheduleBlockRules;
    private final ProviderWriteOutboxService providerWriteOutboxService;
    private final AiRequestAgentService aiRequestAgentService;

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
        return aiRequestAgentService.resolveManualRequest(user, request.reason(), buildAiContext(user, request));
    }

    @Transactional
    public RescheduleSuggestionResponse createChatSuggestion(
            ChatCommandNormalizationService.NormalizedChatCommand normalizedChatCommand
    ) {
        AppUser user = currentUserProvider.getCurrentUser();
        StructuredAiCommandBatch batch = aiRequestAgentService.resolvePrebuiltCommandBatch(
                user.getId(),
                normalizedChatCommand.commandBatch()
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
                batch
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
        RescheduleSuggestion suggestion = getOwnedSuggestion(user.getId(), suggestionId);
        if (suggestion.getStatus() != RescheduleSuggestionStatus.PENDING) {
            throw new IllegalStateException("대기 중인 suggestion만 적용할 수 있습니다.");
        }

        StructuredAiCommandBatch batch = readBatch(suggestion.getSuggestionPayload());
        List<AppliedCommandSnapshot> snapshots = new ArrayList<>();
        for (StructuredAiCommand command : batch.commands()) {
            snapshots.add(applyCommandSafely(user.getId(), command));
        }

        suggestion.setStatus(RescheduleSuggestionStatus.APPLIED);
        suggestion.setAppliedAt(Instant.now());
        suggestion.setExecutionSnapshot(writeJson(snapshots));
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            suggestion.setReason(request.reason().trim());
        }
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    @Transactional
    public RescheduleSuggestionResponse rejectSuggestion(UUID suggestionId, SuggestionDecisionRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        RescheduleSuggestion suggestion = getOwnedSuggestion(user.getId(), suggestionId);
        if (suggestion.getStatus() != RescheduleSuggestionStatus.PENDING) {
            throw new IllegalStateException("대기 중인 suggestion만 거절할 수 있습니다.");
        }

        suggestion.setStatus(RescheduleSuggestionStatus.REJECTED);
        suggestion.setRejectedAt(Instant.now());
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            suggestion.setReason(request.reason().trim());
        }
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    @Transactional
    public RescheduleSuggestionResponse revertSuggestion(UUID suggestionId, String revertReason) {
        AppUser user = currentUserProvider.getCurrentUser();
        RescheduleSuggestion suggestion = getOwnedSuggestion(user.getId(), suggestionId);
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
            suggestion.setReason(revertReason.trim());
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
        suggestion.setExplanation(explanation);
        suggestion.setSuggestionPayload(writeJson(batch));
        return toResponse(rescheduleSuggestionRepository.save(suggestion));
    }

    private RescheduleSuggestion getOwnedSuggestion(UUID userId, UUID suggestionId) {
        return rescheduleSuggestionRepository.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 suggestion을 찾을 수 없습니다."));
    }

    private RescheduleSuggestionResponse toResponse(RescheduleSuggestion suggestion) {
        StructuredAiCommandBatch batch = readBatch(suggestion.getSuggestionPayload());
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
                suggestion.getExplanation(),
                batch,
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

        int appliedCount = (int) snapshots.stream()
                .filter(snapshot -> "applied".equals(snapshot.outcome()))
                .count();
        int noOpCount = (int) snapshots.stream()
                .filter(snapshot -> "no_op".equals(snapshot.outcome()))
                .count();

        return new SuggestionExecutionSummaryResponse(
                snapshots.size(),
                appliedCount,
                noOpCount,
                "%d개 명령 적용, %d개 명령 기록".formatted(appliedCount, noOpCount)
        );
    }

    private String statusLabel(RescheduleSuggestionStatus status) {
        return switch (status) {
            case PENDING -> "검토 대기";
            case APPLIED -> "적용 완료";
            case REJECTED -> "보류됨";
            case REVERTED -> "되돌림 완료";
        };
    }

    private String statusDetail(
            RescheduleSuggestion suggestion,
            SuggestionExecutionSummaryResponse executionSummary
    ) {
        return switch (suggestion.getStatus()) {
            case PENDING -> "검토 후 적용하거나 보류할 수 있습니다.";
            case APPLIED -> executionSummary == null
                    ? "제안을 적용했습니다."
                    : executionSummary.detail();
            case REJECTED -> suggestion.getReason() == null || suggestion.getReason().isBlank()
                    ? "제안을 보류했습니다."
                    : suggestion.getReason();
            case REVERTED -> suggestion.getReason() == null || suggestion.getReason().isBlank()
                    ? "적용한 제안을 되돌렸습니다."
                    : suggestion.getReason();
        };
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

    private AppliedCommandSnapshot applyCommand(UUID userId, StructuredAiCommand command) {
        AgentCommandActionType actionType = AgentCommandActionType.from(command.actionType());
        AgentCommandTargetType targetType = AgentCommandTargetType.from(command.targetType());

        if (targetType == AgentCommandTargetType.TASK || actionType == AgentCommandActionType.RECOMMEND_TASK) {
            return applyTaskCommand(userId, command, actionType);
        }

        if (targetType == AgentCommandTargetType.EVENT && command.targetId() != null && !command.targetId().isBlank()) {
            Optional<Event> event = eventRepository.findByIdAndUserId(UUID.fromString(command.targetId()), userId);
            if (event.isPresent()) {
                return switch (actionType) {
                    case MOVE_EVENT -> moveEvent(event.get(), command);
                    case UPDATE_EVENT -> updateEvent(event.get(), command);
                    case DELETE_EVENT -> deleteEvent(event.get(), command);
                    default -> noExecutableTarget(command, "canonical event에 적용할 수 없는 명령입니다.");
                };
            }
        }

        if (targetType == AgentCommandTargetType.EVENT
                && actionType == AgentCommandActionType.CREATE_EVENT
                && readInstant(command.payload(), "startAt", "start_at") != null
                && readInstant(command.payload(), "endAt", "end_at") != null) {
            return createEvent(userId, command);
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

    private AppliedCommandSnapshot applyCommandSafely(UUID userId, StructuredAiCommand command) {
        try {
            return applyCommand(userId, command);
        } catch (DateTimeException | IllegalArgumentException | IllegalStateException exception) {
            return noExecutableTarget(command, "명령 적용 중 검증 오류가 발생해 건너뛰었습니다: " + exception.getMessage());
        }
    }

    private AppliedCommandSnapshot applyTaskCommand(
            UUID userId,
            StructuredAiCommand command,
            AgentCommandActionType actionType
    ) {
        return switch (actionType) {
            case CREATE_EVENT, RECOMMEND_TASK -> createTask(userId, command);
            case MOVE_EVENT -> moveTask(userId, command);
            case UPDATE_EVENT -> updateTask(userId, command);
            case DELETE_EVENT -> deleteTask(userId, command);
            default -> noExecutableTarget(command, "canonical task에 적용할 수 없는 명령입니다.");
        };
    }

    private AppliedCommandSnapshot createEvent(UUID userId, StructuredAiCommand command) {
        Event event = new Event();
        event.setUserId(userId);
        event.setSourceType(EventSourceType.AGENT_GENERATED);
        applyEventPayload(event, command.payload(), true);
        Event saved = eventRepository.save(event);
        providerWriteOutboxService.enqueueEventWrite(saved, ProviderWriteOperation.CREATE);
        Event persisted = eventRepository.save(saved);
        return new AppliedCommandSnapshot(
                command.actionType(),
                persisted.getId().toString(),
                "applied",
                null,
                null,
                "create_event 제안을 canonical event에 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot moveEvent(Event event, StructuredAiCommand command) {
        Long shiftMinutes = readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");
        Instant explicitStartAt = readInstant(command.payload(), "startAt", "start_at");
        Instant explicitEndAt = readInstant(command.payload(), "endAt", "end_at");
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
        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
        eventRepository.save(event);
        return new AppliedCommandSnapshot(
                command.actionType(),
                event.getId().toString(),
                "applied",
                null,
                null,
                "move_event 제안을 canonical event에 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot updateEvent(Event event, StructuredAiCommand command) {
        applyEventPayload(event, command.payload(), false);
        validateEventRange(event);
        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
        eventRepository.save(event);
        return new AppliedCommandSnapshot(
                command.actionType(),
                event.getId().toString(),
                "applied",
                null,
                null,
                "update_event 제안을 canonical event에 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot deleteEvent(Event event, StructuredAiCommand command) {
        event.setStatus(EventStatus.CANCELLED);
        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.DELETE);
        eventRepository.save(event);
        return new AppliedCommandSnapshot(
                command.actionType(),
                event.getId().toString(),
                "applied",
                null,
                null,
                "delete_event 제안을 canonical event 취소로 반영하고 provider delete를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot createTask(UUID userId, StructuredAiCommand command) {
        Task task = new Task();
        task.setUserId(userId);
        task.setSourceType(TaskSourceType.AGENT_GENERATED);
        applyTaskPayload(task, command.payload(), true);
        Task saved = taskRepository.save(task);
        providerWriteOutboxService.enqueueTaskWrite(saved, ProviderWriteOperation.CREATE);
        Task persisted = taskRepository.save(saved);
        return new AppliedCommandSnapshot(
                command.actionType(),
                persisted.getId().toString(),
                "applied",
                null,
                null,
                "할 일 제안을 canonical task에 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot moveTask(UUID userId, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        Long shiftMinutes = readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes");
        Instant dueDate = readInstant(command.payload(), "dueDate", "due_date");
        if (dueDate != null) {
            task.setDueDate(dueDate);
        } else if (shiftMinutes != null && task.getDueDate() != null) {
            task.setDueDate(task.getDueDate().plusSeconds(shiftMinutes * 60L));
        } else {
            return noExecutableTarget(command, "할 일 이동을 위한 dueDate 또는 기존 dueDate와 이동 시간이 필요합니다.");
        }
        providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
        taskRepository.save(task);
        return new AppliedCommandSnapshot(
                command.actionType(),
                task.getId().toString(),
                "applied",
                null,
                null,
                "move_event 제안을 canonical task 마감 조정으로 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot updateTask(UUID userId, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        applyTaskPayload(task, command.payload(), false);
        providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
        taskRepository.save(task);
        return new AppliedCommandSnapshot(
                command.actionType(),
                task.getId().toString(),
                "applied",
                null,
                null,
                "update_event 제안을 canonical task에 반영하고 provider write를 예약했습니다."
        );
    }

    private AppliedCommandSnapshot deleteTask(UUID userId, StructuredAiCommand command) {
        Task task = getOwnedTask(userId, command);
        task.setStatus(TaskStatus.CANCELLED);
        providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.DELETE);
        taskRepository.save(task);
        return new AppliedCommandSnapshot(
                command.actionType(),
                task.getId().toString(),
                "applied",
                null,
                null,
                "delete_event 제안을 canonical task 취소로 반영하고 provider delete를 예약했습니다."
        );
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
                "move_event 제안을 schedule block에 반영했습니다."
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
                "update_event 제안을 schedule block에 반영했습니다."
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
                "create_event 제안을 schedule block으로 생성했습니다."
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
                "delete_event 제안을 schedule block 삭제로 반영했습니다."
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
        AgentCommandActionType actionType = AgentCommandActionType.from(snapshot.actionType());
        switch (actionType) {
            case CREATE_EVENT -> {
                if (snapshot.afterState() != null) {
                    scheduleBlockRepository.findByIdAndUserId(UUID.fromString(snapshot.afterState().id()), userId)
                            .ifPresent(scheduleBlockRepository::delete);
                }
            }
            case MOVE_EVENT, UPDATE_EVENT, DELETE_EVENT -> {
                if (snapshot.beforeState() != null) {
                    restoreSnapshot(userId, snapshot.beforeState());
                }
            }
            default -> {
            }
        }
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
            block.setActivity(activity.trim());
        }
        if (category != null) {
            block.setCategory(ScheduleCategory.valueOf(category.toUpperCase()));
        } else if (requireAllFields) {
            block.setCategory(ScheduleCategory.LIFE);
        }
        if (note != null || requireAllFields) {
            block.setNote(note == null || note.isBlank() ? null : note.trim());
        }
    }

    private void applyEventPayload(Event event, Map<String, Object> payload, boolean requireTimeRange) {
        String title = readString(payload, "title", "activity", "summary");
        String description = readString(payload, "description", "note");
        Instant startAt = readInstant(payload, "startAt", "start_at");
        Instant endAt = readInstant(payload, "endAt", "end_at");
        String category = readString(payload, "category");
        String priority = readString(payload, "priority");
        String goalId = readString(payload, "goalId", "goal_id");

        if (requireTimeRange && (startAt == null || endAt == null)) {
            throw new IllegalArgumentException("canonical event 생성에는 startAt과 endAt이 필요합니다.");
        }
        if (title != null) {
            event.setTitle(title.trim());
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
            event.setPriority(Short.parseShort(priority));
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

    private void applyTaskPayload(Task task, Map<String, Object> payload, boolean requireTitle) {
        String title = readString(payload, "title", "activity", "summary");
        String description = readString(payload, "description", "note");
        Instant dueDate = readInstant(payload, "dueDate", "due_date");
        String estimatedMinutes = readString(payload, "estimatedMinutes", "estimated_minutes");
        String priority = readString(payload, "priority");
        String goalId = readString(payload, "goalId", "goal_id");
        String category = readString(payload, "category");

        if (title != null) {
            task.setTitle(title.trim());
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
            task.setEstimatedMinutes(Integer.parseInt(estimatedMinutes));
        }
        if (priority != null) {
            task.setPriority(Short.parseShort(priority));
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

    private AiRescheduleClient.RescheduleAiContext buildAiContext(AppUser user, ManualRescheduleRequest request) {
        List<Event> events = (request.targetRangeStart() != null && request.targetRangeEnd() != null)
                ? eventRepository.findByUserIdAndStatusNotAndStartAtBetweenOrderByStartAtAsc(
                user.getId(),
                EventStatus.CANCELLED,
                request.targetRangeStart(),
                request.targetRangeEnd()
        )
                : eventRepository.findByUserIdOrderByStartAtAsc(user.getId());

        return new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext(
                        user.getId().toString(),
                        blankToDefault(user.getTimezone(), "Asia/Seoul")
                ),
                new AiRescheduleClient.RequestContext(
                        request.triggerType(),
                        request.reason(),
                        request.targetRangeStart() == null ? null : request.targetRangeStart().toString(),
                        request.targetRangeEnd() == null ? null : request.targetRangeEnd().toString()
                ),
                scheduleBlockRepository.findByUserId(user.getId()).stream()
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
                        .toList()
        );
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

    private Instant readInstant(Map<String, Object> payload, String... keys) {
        String value = readString(payload, keys);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return LocalDateTime.parse(value).atZone(DEFAULT_AI_LOCAL_DATE_TIME_ZONE).toInstant();
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
            String explanation,
            StructuredAiCommandBatch commandBatch,
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
            String detail
    ) {
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
}
