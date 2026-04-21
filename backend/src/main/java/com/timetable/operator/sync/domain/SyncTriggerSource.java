package com.timetable.operator.sync.domain;

public enum SyncTriggerSource {
    MANUAL("manual"),
    WEBHOOK("webhook"),
    POLLING("polling");

    private final String wireValue;

    SyncTriggerSource(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
