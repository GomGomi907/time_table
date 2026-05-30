package com.timetable.operator.agent.application;

import java.util.List;

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
