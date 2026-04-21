package com.timetable.operator.agent.domain;

public enum RescheduleSuggestionStatus {
    PENDING("pending"),
    APPLIED("applied"),
    REJECTED("rejected"),
    REVERTED("reverted");

    private final String wireValue;

    RescheduleSuggestionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
