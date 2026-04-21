package com.timetable.operator.sync.domain;

public enum SyncExecutionStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCESS("success"),
    PARTIAL_FAILURE("partial_failure"),
    FAILED("failed");

    private final String wireValue;

    SyncExecutionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
