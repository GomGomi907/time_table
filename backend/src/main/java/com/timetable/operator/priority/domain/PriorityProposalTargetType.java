package com.timetable.operator.priority.domain;

import java.util.Locale;

public enum PriorityProposalTargetType {
    EVENT("event"),
    TASK("task"),
    GOAL("goal");

    private final String wireValue;

    PriorityProposalTargetType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static PriorityProposalTargetType from(String value) {
        if (value == null || value.isBlank()) {
            return EVENT;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return PriorityProposalTargetType.valueOf(normalized);
    }
}
