package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.schedule.application.ScheduleBlockRules;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCommandValidationService {

    private final ScheduleBlockRepository scheduleBlockRepository;
    private final ScheduleBlockRules scheduleBlockRules;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;

    public StructuredAiCommandBatch requireExecutableOrClarification(UUID userId, StructuredAiCommandBatch batch) {
        return requireExecutableOrClarification(userId, null, batch);
    }

    public StructuredAiCommandBatch requireExecutableOrClarification(UUID userId, String timezone, StructuredAiCommandBatch batch) {
        if (batch == null || batch.commands() == null || batch.commands().isEmpty()) {
            return clarificationBatch(
                    "어떤 일정이나 할 일을 바꿀지 확인이 필요합니다. 원하는 내용과 날짜를 알려주세요.",
                    List.of("action"),
                    List.of(),
                    "empty_command_batch"
            );
        }

        boolean hasSpecificMutationCommand = batch.commands().stream()
                .map(this::readActionType)
                .flatMap(Optional::stream)
                .anyMatch(actionType -> actionType != AgentCommandActionType.REQUEST_RESCHEDULE
                        && actionType != AgentCommandActionType.EXPLAIN_ONLY
                        && actionType != AgentCommandActionType.RUN_SYNC);

        ZoneId userZone = AiLocalDateTimeParser.resolveUserZone(timezone);
        List<ValidationIssue> issues = new ArrayList<>();
        int executableCount = 0;
        for (StructuredAiCommand command : batch.commands()) {
            Optional<AgentCommandActionType> actionType = readActionType(command);
            if (hasSpecificMutationCommand && actionType.isPresent()
                    && actionType.get() == AgentCommandActionType.REQUEST_RESCHEDULE) {
                continue;
            }
            CommandValidation validation = validateCommand(userId, userZone, command);
            if (validation.executable()) {
                executableCount++;
            }
            issues.addAll(validation.issues());
        }

        if (!issues.isEmpty()) {
            ValidationIssue firstIssue = issues.getFirst();
            log.info(
                    "AI command blocked before execution: reason={}, missing={}, ambiguous={}",
                    firstIssue.reason(),
                    firstIssue.missingFields(),
                    firstIssue.ambiguousFields()
            );
            return clarificationBatch(
                    firstIssue.question(),
                    firstIssue.missingFields(),
                    firstIssue.ambiguousFields(),
                    firstIssue.reason()
            );
        }
        if (executableCount == 0) {
            return clarificationBatch(
                    "바꿀 수 있는 일정이나 할 일을 찾지 못했습니다. 원하는 변경을 한 문장으로 알려주세요.",
                    List.of("executableCommand"),
                    List.of(),
                    "no_executable_command"
            );
        }
        return batch;
    }

    public StructuredAiCommandBatch aiDisabledBatch(String reason) {
        return nonExecutableBatch(
                "AI 설정이 필요합니다",
                "AI 기능이 꺼져 있어 변경 내용을 만들지 못했습니다.",
                "AI 설정을 확인한 뒤 다시 시도해 주세요.",
                "provider_unavailable",
                reason
        );
    }

    public StructuredAiCommandBatch providerUnavailableBatch(String reason) {
        return providerUnavailableBatch(reason, null);
    }

    public StructuredAiCommandBatch providerUnavailableBatch(String reason, RuntimeException exception) {
        String visibleMessage = providerUnavailableMessage(exception);
        return nonExecutableBatch(
                "AI 요청 처리 실패",
                "AI 응답을 받지 못해 변경 내용을 만들지 못했습니다.",
                visibleMessage,
                "provider_unavailable",
                reason
        );
    }

    private String providerUnavailableMessage(RuntimeException exception) {
        String message = exception == null ? "" : String.valueOf(exception.getMessage()).toLowerCase();
        if (message.contains("resource_exhausted")
                || message.contains("prepayment credits")
                || message.contains("quota")
                || message.contains("429")
                || message.contains("한도")
                || message.contains("크레딧")) {
            return "AI 사용량 한도가 소진되어 요청을 처리하지 못했습니다.";
        }
        return "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    public StructuredAiCommandBatch clarificationRequiredBatch(
            String question,
            List<String> missingFields,
            List<String> ambiguousFields,
            String reason
    ) {
        return clarificationBatch(question, missingFields, ambiguousFields, reason);
    }

    private StructuredAiCommandBatch nonExecutableBatch(
            String summary,
            String explanation,
            String visibleMessage,
            String resolutionType,
            String reason
    ) {
        return new StructuredAiCommandBatch(
                summary,
                explanation,
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of(
                                "resolutionType", resolutionType,
                                "message", visibleMessage
                        ),
                        reason == null || reason.isBlank() ? resolutionType : reason,
                        false
                ))
        );
    }

    private CommandValidation validateCommand(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        if (command == null) {
            return invalid("변경 내용을 읽지 못했습니다. 한 번만 다시 보내 주세요.", List.of("command"), List.of(), "null_command");
        }

        AgentCommandActionType actionType;
        AgentCommandTargetType targetType;
        try {
            actionType = AgentCommandActionType.from(command.actionType());
            targetType = AgentCommandTargetType.from(command.targetType());
        } catch (IllegalArgumentException exception) {
            return invalid("지원하지 않는 변경 형식입니다. 일정이나 할 일 추가, 수정, 삭제로 요청해 주세요.",
                    List.of("actionType"), List.of(), "unsupported_action_or_target");
        }

        if (actionType == AgentCommandActionType.EXPLAIN_ONLY || actionType == AgentCommandActionType.RUN_SYNC) {
            return CommandValidation.nonExecutableResult();
        }
        if (!command.requiresConfirmation()) {
            return invalid("반영 전 확인이 필요합니다. 확인할 변경을 다시 알려주세요.",
                    List.of("requiresConfirmation"), List.of(), "missing_confirmation_flag");
        }
        if (actionType == AgentCommandActionType.REQUEST_RESCHEDULE) {
            return invalid("조정 범위가 넓습니다. 기간과 기준을 조금 더 알려주세요.",
                    List.of("target", "timeRange"), List.of(), "broad_reschedule_request");
        }
        if (isTaskCommand(actionType, targetType)) {
            return validateTaskCommand(userId, userZone, command, actionType);
        }
        if (targetType == AgentCommandTargetType.EVENT) {
            return validateEventOrScheduleCommand(userId, userZone, command, actionType);
        }
        return invalid("지원하는 대상은 일정 또는 할 일입니다. 어느 쪽을 바꿀지 명확히 알려주세요.",
                List.of("targetType"), List.of(String.valueOf(command.targetType())), "unsupported_target_type");
    }

    private Optional<AgentCommandActionType> readActionType(StructuredAiCommand command) {
        if (command == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AgentCommandActionType.from(command.actionType()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
    private boolean isTaskCommand(AgentCommandActionType actionType, AgentCommandTargetType targetType) {
        return targetType == AgentCommandTargetType.TASK || actionType == AgentCommandActionType.RECOMMEND_TASK;
    }

    private CommandValidation validateTaskCommand(UUID userId, ZoneId userZone, StructuredAiCommand command, AgentCommandActionType actionType) {
        return switch (actionType) {
            case CREATE_EVENT, RECOMMEND_TASK -> requirePayloadTitle(command, "할 일 제목을 알려주세요. 예: '오늘 할 일에 세금 신고 추가'.");
            case UPDATE_EVENT -> validateExistingTaskMutation(userId, userZone, command, true, false, "수정할 할 일 ID와 바꿀 내용이 필요합니다.");
            case MOVE_EVENT -> validateExistingTaskMutation(userId, userZone, command, false, true, "옮길 할 일 ID와 새 마감 또는 이동 시간이 필요합니다.");
            case DELETE_EVENT -> validateExistingTaskMutation(userId, userZone, command, false, false, "삭제할 할 일 ID가 필요합니다.");
            default -> invalid("할 일에는 추가, 수정, 이동, 삭제만 적용할 수 있습니다.",
                    List.of("actionType"), List.of(), "unsupported_task_action");
        };
    }

    private CommandValidation validateExistingTaskMutation(
            UUID userId,
            ZoneId userZone,
            StructuredAiCommand command,
            boolean requireChangedField,
            boolean requireMoveField,
            String question
    ) {
        Optional<UUID> targetId = parseTargetId(command.targetId());
        if (targetId.isEmpty()) {
            return invalid(question, List.of("targetId"), List.of(), "missing_task_target");
        }
        Optional<Task> task = taskRepository.findByIdAndUserId(targetId.get(), userId);
        if (task.isEmpty()) {
            return invalid("해당 할 일을 찾지 못했습니다. 할 일 제목을 다시 알려주세요.",
                    List.of("targetId"), List.of(command.targetId()), "task_target_not_found");
        }
        String rawDueDate = readString(command.payload(), "dueDate", "due_date");
        Instant dueDate;
        try {
            dueDate = readInstant(userZone, command.payload(), "dueDate", "due_date");
        } catch (AiDateTimeFormatException exception) {
            return invalid("마감 시각을 확인하지 못했습니다. 날짜와 시간을 다시 알려주세요.",
                    List.of("dueDate"), List.of(), "invalid_task_due_date_format");
        }
        if (requireMoveField && dueDate == null
                && readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes") == null) {
            return invalid(question, List.of("dueDate", "suggestedShiftMinutes"), List.of(), "missing_task_move_field");
        }
        if (requireMoveField && readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes") != null
                && task.get().getDueDate() == null
                && dueDate == null) {
            return invalid("기존 마감이 없는 할 일은 몇 분 미루기보다 새 마감을 명확히 지정해야 합니다.",
                    List.of("dueDate"), List.of(), "task_missing_existing_due_date");
        }
        if (requireChangedField && !hasAnyPayload(command, "title", "activity", "summary", "description", "note", "dueDate", "due_date", "estimatedMinutes", "estimated_minutes", "priority", "goalId", "goal_id", "category")) {
            return invalid(question, List.of("changedField"), List.of(), "missing_task_update_field");
        }
        return CommandValidation.executableResult();
    }

    private CommandValidation validateEventOrScheduleCommand(UUID userId, ZoneId userZone, StructuredAiCommand command, AgentCommandActionType actionType) {
        return switch (actionType) {
            case CREATE_EVENT -> validateCreateEventOrSchedule(userId, userZone, command);
            case UPDATE_EVENT -> validateExistingEventOrScheduleMutation(userId, userZone, command, true, false, "수정할 일정 ID와 바꿀 내용이 필요합니다.");
            case MOVE_EVENT -> validateExistingEventOrScheduleMutation(userId, userZone, command, false, true, "옮길 일정 ID와 새 시간 또는 이동 시간이 필요합니다.");
            case DELETE_EVENT -> validateExistingEventOrScheduleMutation(userId, userZone, command, false, false, "삭제할 일정 ID가 필요합니다.");
            default -> invalid("일정에는 추가, 수정, 이동, 삭제만 적용할 수 있습니다.",
                    List.of("actionType"), List.of(), "unsupported_event_action");
        };
    }

    private CommandValidation validateCreateEventOrSchedule(UUID userId, ZoneId userZone, StructuredAiCommand command) {
        String rawStartAt = readString(command.payload(), "startAt", "start_at");
        String rawEndAt = readString(command.payload(), "endAt", "end_at");
        Instant startAt;
        Instant endAt;
        try {
            startAt = readInstant(userZone, command.payload(), "startAt", "start_at");
            endAt = readInstant(userZone, command.payload(), "endAt", "end_at");
        } catch (AiDateTimeFormatException exception) {
            return invalid("일정 시작/종료 시각을 확인하지 못했습니다. 날짜와 시간을 다시 알려주세요.",
                    List.of("startAt", "endAt"), List.of(), "invalid_event_time_range");
        }
        if (rawStartAt != null || rawEndAt != null || startAt != null || endAt != null) {
            if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
                return invalid("일정 시작/종료 시각을 확인하지 못했습니다. 날짜와 시간을 다시 알려주세요.",
                        List.of("startAt", "endAt"), List.of(), "invalid_event_time_range");
            }
            return requirePayloadTitle(command, "일정 제목을 알려주세요.");
        }

        List<String> missing = new ArrayList<>();
        if (readString(command.payload(), "dayOfWeek", "day_of_week") == null) {
            missing.add("dayOfWeek");
        }
        if (readString(command.payload(), "startTime", "start_time") == null) {
            missing.add("startTime");
        }
        if (readString(command.payload(), "endTime", "end_time") == null) {
            missing.add("endTime");
        }
        if (readString(command.payload(), "activity", "title", "summary") == null) {
            missing.add("activity");
        }
        if (!missing.isEmpty()) {
            return invalid("일정 정보를 더 확인해야 합니다. 예: '수요일 15:00-16:00 회의 추가'.",
                    missing, List.of(), "missing_schedule_create_fields");
        }
        try {
            DayOfWeek.valueOf(readString(command.payload(), "dayOfWeek", "day_of_week").toUpperCase());
            LocalTime start = LocalTime.parse(readString(command.payload(), "startTime", "start_time"));
            LocalTime end = LocalTime.parse(readString(command.payload(), "endTime", "end_time"));
            if (!end.isAfter(start)) {
                return invalid("종료 시간은 시작 시간보다 늦어야 합니다. 예: 15:00-16:00처럼 입력해 주세요.",
                        List.of("endTime"), List.of(), "invalid_schedule_time_range");
            }
            String category = readString(command.payload(), "category");
            ScheduleBlock candidate = new ScheduleBlock();
            candidate.setUserId(userId);
            candidate.setDayOfWeek(DayOfWeek.valueOf(readString(command.payload(), "dayOfWeek", "day_of_week").toUpperCase()));
            candidate.setStartTime(start);
            candidate.setEndTime(end);
            candidate.setActivity(readString(command.payload(), "activity", "title", "summary"));
            candidate.setCategory(category == null ? ScheduleCategory.LIFE : ScheduleCategory.valueOf(category.toUpperCase()));
            candidate.setNote(readString(command.payload(), "note", "description"));
            scheduleBlockRules.validateForUser(userId, candidate);
        } catch (RuntimeException exception) {
            return invalid("시간이나 분류를 확인하지 못했거나 기존 일정과 겹칩니다. 다른 시간을 알려주세요.",
                    List.of("dayOfWeek", "startTime", "endTime"), List.of(), "invalid_schedule_create_fields");
        }
        return CommandValidation.executableResult();
    }

    private CommandValidation requirePayloadTitle(StructuredAiCommand command, String question) {
        if (readString(command.payload(), "title", "activity", "summary") == null) {
            return invalid(question, List.of("title"), List.of(), "missing_title");
        }
        return CommandValidation.executableResult();
    }

    private CommandValidation validateExistingEventOrScheduleMutation(
            UUID userId,
            ZoneId userZone,
            StructuredAiCommand command,
            boolean requireChangedField,
            boolean requireMoveField,
            String question
    ) {
        Optional<UUID> targetId = parseTargetId(command.targetId());
        if (targetId.isEmpty()) {
            return invalid(question, List.of("targetId"), List.of(), "missing_event_target");
        }
        Optional<Event> event = eventRepository.findByIdAndUserId(targetId.get(), userId);
        if (event.isPresent()) {
            return validateCanonicalEventMutation(userZone, command, requireChangedField, requireMoveField, question);
        }
        Optional<ScheduleBlock> block = scheduleBlockRepository.findByIdAndUserId(targetId.get(), userId);
        if (block.isPresent()) {
            return validateScheduleBlockMutation(command, requireChangedField, requireMoveField, question);
        }
        return invalid("해당 일정을 찾지 못했습니다. 일정 제목을 다시 알려주세요.",
                List.of("targetId"), List.of(command.targetId()), "event_target_not_found");
    }

    private CommandValidation validateCanonicalEventMutation(
            ZoneId userZone,
            StructuredAiCommand command,
            boolean requireChangedField,
            boolean requireMoveField,
            String question
    ) {
        if (requireMoveField) {
            String rawStartAt = readString(command.payload(), "startAt", "start_at");
            String rawEndAt = readString(command.payload(), "endAt", "end_at");
            Instant startAt;
            Instant endAt;
            try {
                startAt = readInstant(userZone, command.payload(), "startAt", "start_at");
                endAt = readInstant(userZone, command.payload(), "endAt", "end_at");
            } catch (AiDateTimeFormatException exception) {
                return invalid("새 시간을 확인하지 못했습니다. 날짜와 시간을 다시 알려주세요.",
                        List.of("startAt", "endAt"), List.of(), "invalid_event_time_format");
            }
            if (startAt == null && endAt == null && readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes") == null) {
                return invalid(question, List.of("startAt", "endAt", "suggestedShiftMinutes"), List.of(), "missing_event_move_field");
            }
            if ((startAt == null) != (endAt == null)) {
                return invalid("새 시작/종료 시각은 함께 지정해 주세요. 예: 2026-05-16T19:10-20:10", List.of("startAt", "endAt"), List.of(), "partial_event_time_range");
            }
            if (startAt != null && !endAt.isAfter(startAt)) {
                return invalid("종료 시각은 시작 시각보다 늦어야 합니다. 다시 확인해 주세요.", List.of("endAt"), List.of(), "invalid_event_time_range");
            }
        }
        if (requireChangedField && !hasAnyPayload(command, "title", "activity", "summary", "description", "note", "startAt", "start_at", "endAt", "end_at", "category", "priority", "goalId", "goal_id")) {
            return invalid(question, List.of("changedField"), List.of(), "missing_event_update_field");
        }
        return CommandValidation.executableResult();
    }

    private CommandValidation validateScheduleBlockMutation(
            StructuredAiCommand command,
            boolean requireChangedField,
            boolean requireMoveField,
            String question
    ) {
        if (requireMoveField && readLong(command.payload(), "suggestedShiftMinutes", "suggested_shift_minutes") == null
                && !hasAnyPayload(command, "dayOfWeek", "day_of_week", "startTime", "start_time", "endTime", "end_time")) {
            return invalid(question, List.of("dayOfWeek", "startTime", "endTime", "suggestedShiftMinutes"), List.of(), "missing_schedule_move_field");
        }
        try {
            if (readString(command.payload(), "dayOfWeek", "day_of_week") != null) {
                DayOfWeek.valueOf(readString(command.payload(), "dayOfWeek", "day_of_week").toUpperCase());
            }
            if (readString(command.payload(), "startTime", "start_time") != null) {
                LocalTime.parse(readString(command.payload(), "startTime", "start_time"));
            }
            if (readString(command.payload(), "endTime", "end_time") != null) {
                LocalTime.parse(readString(command.payload(), "endTime", "end_time"));
            }
        } catch (RuntimeException exception) {
            return invalid("요일이나 시간 형식이 올바르지 않습니다. 예: MONDAY, 15:00처럼 지정해 주세요.",
                    List.of("dayOfWeek", "startTime", "endTime"), List.of(), "invalid_schedule_update_fields");
        }
        if (requireChangedField && !hasAnyPayload(command, "dayOfWeek", "day_of_week", "startTime", "start_time", "endTime", "end_time", "activity", "title", "summary", "category", "note", "description")) {
            return invalid(question, List.of("changedField"), List.of(), "missing_schedule_update_field");
        }
        return CommandValidation.executableResult();
    }

    private StructuredAiCommandBatch clarificationBatch(
            String question,
            List<String> missingFields,
            List<String> ambiguousFields,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resolutionType", "clarification_required");
        payload.put("clarificationQuestion", question);
        payload.put("missingFields", missingFields == null ? List.of() : missingFields);
        payload.put("ambiguousFields", ambiguousFields == null ? List.of() : ambiguousFields);
        payload.put("reason", reason);
        return new StructuredAiCommandBatch(
                "확인이 필요합니다",
                question,
                List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.copyOf(payload),
                        reason,
                        false
                ))
        );
    }

    private CommandValidation invalid(String question, List<String> missingFields, List<String> ambiguousFields, String reason) {
        return new CommandValidation(false, List.of(new ValidationIssue(question, missingFields, ambiguousFields, reason)));
    }

    private Optional<UUID> parseTargetId(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(targetId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean hasAnyPayload(StructuredAiCommand command, String... keys) {
        if (command.payload() == null) {
            return false;
        }
        for (String key : keys) {
            if (command.payload().containsKey(key) && command.payload().get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private Long readLong(Map<String, Object> payload, String... keys) {
        String value = readString(payload, keys);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Instant readInstant(ZoneId userZone, Map<String, Object> payload, String... keys) {
        return AiLocalDateTimeParser.parseNullable(readString(payload, keys), userZone);
    }

    private record CommandValidation(boolean executable, List<ValidationIssue> issues) {
        private static CommandValidation executableResult() {
            return new CommandValidation(true, List.of());
        }

        private static CommandValidation nonExecutableResult() {
            return new CommandValidation(false, List.of());
        }
    }

    private record ValidationIssue(
            String question,
            List<String> missingFields,
            List<String> ambiguousFields,
            String reason
    ) {
    }
}
