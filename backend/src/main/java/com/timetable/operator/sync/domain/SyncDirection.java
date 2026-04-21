package com.timetable.operator.sync.domain;

public enum SyncDirection {
    INBOUND("inbound"),
    OUTBOUND("outbound");

    private final String wireValue;

    SyncDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SyncDirection from(String value) {
        if (value == null || value.isBlank()) {
            return INBOUND;
        }

        return switch (value.trim().toLowerCase()) {
            case "outbound" -> OUTBOUND;
            default -> INBOUND;
        };
    }
}
