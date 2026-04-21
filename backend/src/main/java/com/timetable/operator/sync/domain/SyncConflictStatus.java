package com.timetable.operator.sync.domain;

public enum SyncConflictStatus {
    PENDING("pending"),
    RESOLVED("resolved");

    private final String wireValue;

    SyncConflictStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
