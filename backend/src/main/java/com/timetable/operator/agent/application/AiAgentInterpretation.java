package com.timetable.operator.agent.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record AiAgentInterpretation(
        String action,
        String targetType,
        String targetId,
        String title,
        String dayOfWeek,
        String startTime,
        String endTime,
        String startAt,
        String endAt,
        Long suggestedShiftMinutes,
        List<String> missingFields,
        List<String> ambiguousFields,
        double confidence,
        boolean repairable,
        String clarificationQuestion
) {
    private static final double EXECUTION_CONFIDENCE_THRESHOLD = 0.78;

    public boolean lowConfidence() {
        return confidence < EXECUTION_CONFIDENCE_THRESHOLD;
    }

    public boolean hasMissingOrAmbiguousFields() {
        return (missingFields != null && !missingFields.isEmpty())
                || (ambiguousFields != null && !ambiguousFields.isEmpty());
    }

    public boolean executableMutationIntent() {
        if (action == null || action.isBlank()) {
            return false;
        }
        String normalized = action.trim().replace('-', '_').replace(' ', '_').toLowerCase();
        return normalized.equals("create")
                || normalized.equals("add")
                || normalized.equals("schedule")
                || normalized.equals("create_event")
                || normalized.equals("recommend_task")
                || normalized.equals("update")
                || normalized.equals("change")
                || normalized.equals("edit")
                || normalized.equals("update_event")
                || normalized.equals("move")
                || normalized.equals("postpone")
                || normalized.equals("advance")
                || normalized.equals("move_event")
                || normalized.equals("delete")
                || normalized.equals("cancel")
                || normalized.equals("remove")
                || normalized.equals("delete_event");
    }

    public boolean canDraftWithAssistantDefaults() {
        if (!isCreateAction() || !isEventTarget() || !hasText(title)) {
            return false;
        }
        boolean hasConcreteStart = hasText(startAt) || hasText(startTime);
        if (!hasConcreteStart) {
            return false;
        }
        if (ambiguousFields != null && !ambiguousFields.isEmpty()) {
            return false;
        }
        List<String> missing = missingFields == null ? List.of() : missingFields;
        if (missing.isEmpty()) {
            return true;
        }
        Set<String> defaultable = Set.of(
                "date", "날짜", "day", "요일",
                "endtime", "end", "종료시간", "종료",
                "duration", "소요시간", "길이"
        );
        return missing.stream()
                .map(AiAgentInterpretation::normalizeField)
                .allMatch(defaultable::contains);
    }

    private boolean isCreateAction() {
        if (action == null) {
            return false;
        }
        String normalized = action.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("create")
                || normalized.equals("add")
                || normalized.equals("schedule")
                || normalized.equals("create_event");
    }

    private boolean isEventTarget() {
        if (targetType == null) {
            return false;
        }
        String normalized = targetType.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("event") || normalized.equals("schedule") || normalized.equals("calendar");
    }

    private static String normalizeField(String field) {
        return field == null
                ? ""
                : field.trim().replace("-", "").replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String safeClarificationQuestion() {
        if (clarificationQuestion != null && !clarificationQuestion.isBlank()) {
            return clarificationQuestion.trim();
        }
        if (missingFields != null && missingFields.stream().anyMatch(field -> field.toLowerCase().contains("time"))) {
            return "언제 진행할까요?";
        }
        if (missingFields != null && missingFields.stream().anyMatch(field -> field.toLowerCase().contains("target"))) {
            return "어떤 항목을 바꿀까요?";
        }
        return "무엇을 바꿀까요?";
    }
}
