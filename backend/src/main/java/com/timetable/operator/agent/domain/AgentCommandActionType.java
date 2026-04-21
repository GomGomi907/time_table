package com.timetable.operator.agent.domain;

import java.util.Locale;

public enum AgentCommandActionType {
    CREATE_EVENT("create_event"),
    UPDATE_EVENT("update_event"),
    MOVE_EVENT("move_event"),
    DELETE_EVENT("delete_event"),
    PROPOSE_PRIORITY("propose_priority"),
    RUN_SYNC("run_sync"),
    REVERT_SUGGESTION("revert_suggestion"),
    EXPLAIN_ONLY("explain_only"),
    REQUEST_RESCHEDULE("request_reschedule"),
    UPDATE_GOAL_PROGRESS("update_goal_progress"),
    CHANGE_SETTINGS("change_settings"),
    RECOMMEND_TASK("recommend_task");

    private final String wireValue;

    AgentCommandActionType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AgentCommandActionType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("action_type 값이 필요합니다.");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return AgentCommandActionType.valueOf(normalized);
    }
}
