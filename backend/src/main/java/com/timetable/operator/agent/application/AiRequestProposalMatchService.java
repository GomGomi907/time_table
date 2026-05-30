package com.timetable.operator.agent.application;

import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiRequestProposalMatchService {

    public MatchResult requireMatch(String requestText, AiAgentInterpretation interpretation, StructuredAiCommandBatch batch) {
        if (interpretation == null) {
            return MatchResult.blocked("missing_interpretation", "요청을 어떻게 처리할지 확인이 필요합니다.", List.of("intent"), false);
        }
        if (interpretation.lowConfidence()) {
            return MatchResult.blocked("low_confidence", interpretation.safeClarificationQuestion(), List.of("confidence"), false);
        }
        if (interpretation.hasMissingOrAmbiguousFields()) {
            return MatchResult.blocked(
                    "missing_or_ambiguous_interpretation",
                    interpretation.safeClarificationQuestion(),
                    firstNonEmpty(interpretation.missingFields()),
                    false
            );
        }
        if (isBroadOrNonMutation(interpretation.action())) {
            return MatchResult.blocked("broad_or_non_mutation_intent", "무엇을 추가/수정/삭제할까요?", List.of("action"), false);
        }
        if (batch == null || batch.commands() == null || batch.commands().isEmpty()) {
            return MatchResult.blocked("empty_batch", "무엇을 바꿀까요?", List.of("command"), true);
        }

        List<StructuredAiCommand> executableCommands = batch.commands().stream()
                .filter(StructuredAiCommand::requiresConfirmation)
                .toList();
        if (executableCommands.isEmpty()) {
            return MatchResult.blocked("no_executable_command", "무엇을 바꿀까요?", List.of("command"), false);
        }

        for (StructuredAiCommand command : executableCommands) {
            MatchResult commandMatch = matchCommand(requestText, interpretation, command);
            if (!commandMatch.matched()) {
                return commandMatch;
            }
        }
        return MatchResult.ok();
    }

    private MatchResult matchCommand(String requestText, AiAgentInterpretation interpretation, StructuredAiCommand command) {
        AgentCommandActionType commandAction;
        AgentCommandTargetType commandTarget;
        try {
            commandAction = AgentCommandActionType.from(command.actionType());
            commandTarget = AgentCommandTargetType.from(command.targetType());
        } catch (IllegalArgumentException exception) {
            return MatchResult.blocked("unsupported_action_or_target", "지원하는 일정/할 일 요청으로 다시 알려주세요.", List.of("action"), false);
        }

        ActionFamily requestedFamily = ActionFamily.from(interpretation.action());
        ActionFamily proposedFamily = ActionFamily.from(commandAction);
        if (requestedFamily == ActionFamily.UNKNOWN || requestedFamily != proposedFamily) {
            return MatchResult.blocked("action_mismatch", "어떤 변경을 할까요?", List.of("action"), true);
        }

        TargetFamily requestedTarget = TargetFamily.from(interpretation.targetType());
        TargetFamily proposedTarget = TargetFamily.from(commandTarget, commandAction);
        if (requestedTarget != TargetFamily.UNKNOWN && requestedTarget != proposedTarget) {
            return MatchResult.blocked("target_type_mismatch", "일정과 할 일 중 무엇을 바꿀까요?", List.of("targetType"), true);
        }

        if (requiresExistingTarget(requestedFamily)) {
            if (isBlank(interpretation.targetId()) || isBlank(command.targetId())) {
                return MatchResult.blocked("missing_target_match", "어떤 항목을 바꿀까요?", List.of("targetId"), false);
            }
            if (!interpretation.targetId().trim().equalsIgnoreCase(command.targetId().trim())) {
                return MatchResult.blocked("target_mismatch", "어떤 항목을 바꿀까요?", List.of("targetId"), true);
            }
        }

        if (requestedFamily == ActionFamily.CREATE && !titleMatches(interpretation, command.payload())) {
            return MatchResult.blocked("title_mismatch", "무엇을 추가할까요?", List.of("title"), true);
        }
        if (!timeMatches(interpretation, command.payload())) {
            return MatchResult.blocked("time_mismatch", "언제로 바꿀까요?", List.of("time"), true);
        }
        if (requestedFamily == ActionFamily.UPDATE && !changedFieldsMatch(requestText, interpretation, command.payload())) {
            return MatchResult.blocked("intent_mismatch", "어떤 내용을 바꿀까요?", List.of("intent"), true);
        }
        if (requestedFamily == ActionFamily.DELETE && !mentionsDeleteIntent(requestText)) {
            return MatchResult.blocked("delete_intent_not_explicit", "삭제할까요?", List.of("intent"), false);
        }
        return MatchResult.ok();
    }

    private boolean titleMatches(AiAgentInterpretation interpretation, Map<String, Object> payload) {
        String expected = normalize(interpretation.title());
        if (expected.isBlank()) {
            return false;
        }
        String actual = normalize(readString(payload, "title", "activity", "summary"));
        return !actual.isBlank() && (actual.contains(expected) || expected.contains(actual));
    }

    private boolean timeMatches(AiAgentInterpretation interpretation, Map<String, Object> payload) {
        if (!equalsWhenExpected(interpretation.dayOfWeek(), readString(payload, "dayOfWeek", "day_of_week"))) {
            return false;
        }
        if (!equalsWhenExpected(interpretation.startTime(), readString(payload, "startTime", "start_time"))) {
            return false;
        }
        if (!equalsWhenExpected(interpretation.endTime(), readString(payload, "endTime", "end_time"))) {
            return false;
        }
        if (!equalsWhenExpected(interpretation.startAt(), readString(payload, "startAt", "start_at"))) {
            return false;
        }
        if (!equalsWhenExpected(interpretation.endAt(), readString(payload, "endAt", "end_at"))) {
            return false;
        }
        Long expectedShift = interpretation.suggestedShiftMinutes();
        if (expectedShift != null) {
            Long actualShift = readLong(payload, "suggestedShiftMinutes", "suggested_shift_minutes");
            return expectedShift.equals(actualShift);
        }
        return true;
    }

    private boolean changedFieldsMatch(String requestText, AiAgentInterpretation interpretation, Map<String, Object> payload) {
        ChangeField requestedField = requestedChangeField(requestText, interpretation);
        return switch (requestedField) {
            case TITLE -> titleMatches(interpretation, payload);
            case CATEGORY -> hasValue(payload, "category");
            case PRIORITY -> hasValue(payload, "priority");
            case DESCRIPTION -> hasValue(payload, "description", "note");
            case TIME -> hasValue(payload, "dayOfWeek", "day_of_week", "startTime", "start_time", "endTime", "end_time",
                    "startAt", "start_at", "endAt", "end_at", "suggestedShiftMinutes", "suggested_shift_minutes");
            case UNKNOWN -> payload != null && !payload.isEmpty();
        };
    }

    private ChangeField requestedChangeField(String requestText, AiAgentInterpretation interpretation) {
        if (hasRequestedTimeChange(interpretation)) {
            return ChangeField.TIME;
        }
        String normalizedRequest = normalize(requestText);
        if (containsAny(normalizedRequest, "category", "분류", "카테고리")) {
            return ChangeField.CATEGORY;
        }
        if (containsAny(normalizedRequest, "priority", "우선순위", "중요도")) {
            return ChangeField.PRIORITY;
        }
        if (containsAny(normalizedRequest, "description", "note", "memo", "설명", "내용", "메모", "노트")) {
            return ChangeField.DESCRIPTION;
        }
        if (containsAny(normalizedRequest, "time", "date", "시간", "날짜", "요일", "오늘", "내일", "모레")) {
            return ChangeField.TIME;
        }
        if (containsAny(normalizedRequest, "title", "name", "activity", "summary", "제목", "이름", "활동")) {
            return ChangeField.TITLE;
        }
        return ChangeField.UNKNOWN;
    }

    private boolean hasRequestedTimeChange(AiAgentInterpretation interpretation) {
        return !isBlank(interpretation.dayOfWeek())
                || !isBlank(interpretation.startTime())
                || !isBlank(interpretation.endTime())
                || !isBlank(interpretation.startAt())
                || !isBlank(interpretation.endAt())
                || interpretation.suggestedShiftMinutes() != null;
    }

    private boolean equalsWhenExpected(String expected, String actual) {
        if (isBlank(expected)) {
            return true;
        }
        if (isBlank(actual)) {
            return false;
        }
        return normalize(expected).equals(normalize(actual));
    }

    private boolean mentionsDeleteIntent(String requestText) {
        String combined = normalize(requestText == null ? "" : requestText);
        return combined.contains("delete") || combined.contains("cancel") || combined.contains("삭제") || combined.contains("취소") || combined.contains("지워");
    }

    private boolean requiresExistingTarget(ActionFamily family) {
        return family == ActionFamily.UPDATE || family == ActionFamily.MOVE || family == ActionFamily.DELETE;
    }

    private boolean isBroadOrNonMutation(String action) {
        ActionFamily family = ActionFamily.from(action);
        return family == ActionFamily.BROAD || family == ActionFamily.EXPLAIN || family == ActionFamily.SYNC;
    }

    private List<String> firstNonEmpty(List<String> values) {
        return values == null || values.isEmpty() ? List.of("field") : values;
    }

    private String readString(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private Long readLong(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean hasValue(Map<String, Object> payload, String... keys) {
        return readString(payload, keys) != null;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum ActionFamily {
        CREATE,
        UPDATE,
        MOVE,
        DELETE,
        BROAD,
        SYNC,
        EXPLAIN,
        UNKNOWN;

        static ActionFamily from(AgentCommandActionType actionType) {
            return switch (actionType) {
                case CREATE_EVENT, RECOMMEND_TASK -> CREATE;
                case UPDATE_EVENT -> UPDATE;
                case MOVE_EVENT -> MOVE;
                case DELETE_EVENT -> DELETE;
                case REQUEST_RESCHEDULE -> BROAD;
                case RUN_SYNC -> SYNC;
                case EXPLAIN_ONLY -> EXPLAIN;
                default -> UNKNOWN;
            };
        }

        static ActionFamily from(String action) {
            if (action == null) {
                return UNKNOWN;
            }
            String normalized = action.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "create", "add", "schedule", "create_event", "recommend_task" -> CREATE;
                case "update", "change", "edit", "update_event" -> UPDATE;
                case "move", "postpone", "advance", "move_event" -> MOVE;
                case "delete", "cancel", "remove", "delete_event" -> DELETE;
                case "request_reschedule", "reschedule", "broad" -> BROAD;
                case "run_sync", "sync" -> SYNC;
                case "explain_only", "explain" -> EXPLAIN;
                default -> UNKNOWN;
            };
        }
    }

    private enum ChangeField {
        TITLE,
        CATEGORY,
        PRIORITY,
        DESCRIPTION,
        TIME,
        UNKNOWN
    }

    private enum TargetFamily {
        EVENT,
        TASK,
        NONE,
        UNKNOWN;

        static TargetFamily from(AgentCommandTargetType targetType, AgentCommandActionType actionType) {
            if (targetType == AgentCommandTargetType.TASK || actionType == AgentCommandActionType.RECOMMEND_TASK) {
                return TASK;
            }
            if (targetType == AgentCommandTargetType.EVENT) {
                return EVENT;
            }
            if (targetType == AgentCommandTargetType.NONE) {
                return NONE;
            }
            return UNKNOWN;
        }

        static TargetFamily from(String targetType) {
            if (targetType == null) {
                return UNKNOWN;
            }
            String normalized = targetType.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "event", "schedule", "calendar", "meeting" -> EVENT;
                case "task", "todo", "to_do" -> TASK;
                case "none" -> NONE;
                default -> UNKNOWN;
            };
        }
    }

    public record MatchResult(
            boolean matched,
            String reason,
            String question,
            List<String> missingFields,
            boolean repairable
    ) {
        static MatchResult ok() {
            return new MatchResult(true, "matched", "", List.of(), false);
        }

        static MatchResult blocked(String reason, String question, List<String> missingFields, boolean repairable) {
            return new MatchResult(false, reason, question, missingFields == null ? List.of() : missingFields, repairable);
        }
    }
}

