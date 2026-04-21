package com.timetable.operator.sync.domain;

public enum SyncTargetSystem {
    GOOGLE_CALENDAR("google_calendar"),
    GOOGLE_TASKS("google_tasks");

    private final String wireValue;

    SyncTargetSystem(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SyncTargetSystem from(String value) {
        if (value == null || value.isBlank()) {
            return GOOGLE_CALENDAR;
        }

        return switch (value.trim().toLowerCase()) {
            case "google_tasks", "google-tasks", "tasks" -> GOOGLE_TASKS;
            default -> GOOGLE_CALENDAR;
        };
    }
}
