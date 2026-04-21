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
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RescheduleSuggestionService {

    private final RescheduleSuggestionRepository rescheduleSuggestionRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    @Transactional
    public RescheduleSuggestionResponse createManualSuggestion(ManualRescheduleRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        StructuredAiCommandBatch batch = new StructuredAiCommandBatch(
                "manual_reschedule_request",
                "재조율 요청을 suggestion 객체로 기록했습니다.",
                List.of(
                        new StructuredAiCommand(
                                AgentCommandActionType.REQUEST_RESCHEDULE.wireValue(),
                                AgentCommandTargetType.EVENT.wireValue(),
                                null,
                                buildManualPayload(request),
                                request.reason(),
                                true
                        ),
                        new StructuredAiCommand(
                                AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                                AgentCommandTargetType.NONE.wireValue(),
                                null,
                                Map.of(
                                        "summary", "실제 재배치 계산은 후속 워커에서 확장할 수 있도록 남겨두었습니다."
                                ),
                                "scaffolded reschedule suggestion",
                                false
                        )
                )
        );

        return createSuggestion(
                user.getId(),
                RescheduleSuggestionTriggerType.from(request.triggerType()),
                "재조율 요청이 접수되었습니다.",
                request.reason(),
                batch.explanation(),
                batch
        );
    }

    @Transactional
    public RescheduleSuggestionResponse createChatSuggestion(
            ChatCommandNormalizationService.NormalizedChatCommand normalizedChatCommand
    ) {
        AppUser user = currentUserProvider.getCurrentUser();
        return createSuggestion(
                user.getId(),
                RescheduleSuggestionTriggerType.MANUAL_REQUEST,
                normalizedChatCommand.commandBatch().summary(),
                normalizedChatCommand.normalizedMessage(),
                normalizedChatCommand.commandBatch().explanation(),
                normalizedChatCommand.commandBatch()
        );
    }

    @Transactional(readOnly = true)
    public List<RescheduleSuggestionResponse> getCurrentUserSuggestions() {
        AppUser user = currentUserProvider.getCurrentUser();
        return rescheduleSuggestionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toResponse)
                .toList();
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
            snapshots.add(applyCommand(user.getId(), command));
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
        return new RescheduleSuggestionResponse(
                suggestion.getId().toString(),
                suggestion.getTriggerType().wireValue(),
                suggestion.getStatus().wireValue(),
                suggestion.getSummary(),
                suggestion.getReason(),
                suggestion.getExplanation(),
                readBatch(suggestion.getSuggestionPayload()),
                suggestion.getCreatedAt(),
                suggestion.getAppliedAt(),
                suggestion.getRejectedAt(),
                suggestion.getRevertedAt()
        );
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

    // The current backend only has schedule blocks, so executable event commands target that model for now.
    private AppliedCommandSnapshot applyCommand(UUID userId, StructuredAiCommand command) {
        AgentCommandActionType actionType = AgentCommandActionType.from(command.actionType());
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

        if (shiftMinutes != null) {
            block.setStartTime(block.getStartTime().plusMinutes(shiftMinutes));
            block.setEndTime(block.getEndTime().plusMinutes(shiftMinutes));
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

        if (shiftMinutes == null && explicitDayOfWeek == null && explicitStartTime == null && explicitEndTime == null) {
            return noExecutableTarget(command, "이동할 시간 정보가 payload에 없습니다.");
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

        applyScheduleBlockPayload(block, command.payload(), false);
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
        String activity = readString(payload, "activity");
        String category = readString(payload, "category");
        String note = readString(payload, "note");

        if (requireAllFields && (dayOfWeek == null || startTime == null || endTime == null || activity == null || category == null)) {
            throw new IllegalArgumentException("create_event payload에는 dayOfWeek, startTime, endTime, activity, category가 필요합니다.");
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
            block.setActivity(activity);
        }
        if (category != null) {
            block.setCategory(ScheduleCategory.valueOf(category.toUpperCase()));
        }
        if (note != null || requireAllFields) {
            block.setNote(note);
        }
    }

    private Map<String, Object> buildManualPayload(ManualRescheduleRequest request) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (request.targetRangeStart() != null) {
            payload.put("targetRangeStart", request.targetRangeStart());
        }
        if (request.targetRangeEnd() != null) {
            payload.put("targetRangeEnd", request.targetRangeEnd());
        }
        return Map.copyOf(payload);
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
            String reason
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
            String summary,
            String reason,
            String explanation,
            StructuredAiCommandBatch commandBatch,
            Instant createdAt,
            Instant appliedAt,
            Instant rejectedAt,
            Instant revertedAt
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
