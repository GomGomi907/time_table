package com.timetable.operator.sync.domain;

import java.util.Locale;

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

        String normalized = value.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "googletasks", "tasks" -> GOOGLE_TASKS;
            case "googlecalendar", "calendar" -> GOOGLE_CALENDAR;
            default -> GOOGLE_CALENDAR;
        };
    }
}
