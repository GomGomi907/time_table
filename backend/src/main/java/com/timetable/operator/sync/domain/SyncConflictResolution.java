package com.timetable.operator.sync.domain;

import java.util.Locale;

public enum SyncConflictResolution {
    ACCEPT_REMOTE("accept_remote"),
    FORK_LOCAL("fork_local"),
    MANUAL_EDIT("manual_edit");

    private final String wireValue;

    SyncConflictResolution(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SyncConflictResolution from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("resolution 값이 필요합니다.");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return SyncConflictResolution.valueOf(normalized);
    }
}
