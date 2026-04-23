package com.timetable.operator.agent.domain;

import java.util.Locale;

public enum RescheduleSuggestionTriggerType {
    MANUAL_REQUEST("manual_request"),
    ONBOARDING_BOOTSTRAP("onboarding_bootstrap"),
    EARLY_COMPLETION("early_completion"),
    DELAY("delay"),
    POSTPONE("postpone"),
    SYNC_CHANGE("sync_change"),
    GAP_DETECTED("gap_detected");

    private final String wireValue;

    RescheduleSuggestionTriggerType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static RescheduleSuggestionTriggerType from(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL_REQUEST;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return RescheduleSuggestionTriggerType.valueOf(normalized);
    }
}
