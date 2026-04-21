package com.timetable.operator.agent.domain;

import java.util.Locale;

public enum AgentCommandTargetType {
    EVENT("event"),
    TASK("task"),
    GOAL("goal"),
    SUGGESTION("suggestion"),
    SYNC("sync"),
    SETTING("setting"),
    PRIORITY_PROPOSAL("priority_proposal"),
    NONE("none");

    private final String wireValue;

    AgentCommandTargetType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AgentCommandTargetType from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return AgentCommandTargetType.valueOf(normalized);
    }
}
