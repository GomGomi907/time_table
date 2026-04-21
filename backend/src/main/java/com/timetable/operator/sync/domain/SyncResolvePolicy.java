package com.timetable.operator.sync.domain;

import java.util.Locale;

public enum SyncResolvePolicy {
    PROPOSAL_FIRST("proposal_first"),
    ACCEPT_REMOTE("accept_remote"),
    MANUAL_EDIT("manual_edit");

    private final String wireValue;

    SyncResolvePolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SyncResolvePolicy from(String value) {
        if (value == null || value.isBlank()) {
            return PROPOSAL_FIRST;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return SyncResolvePolicy.valueOf(normalized);
    }
}
