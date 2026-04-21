package com.timetable.operator.agent.domain;

public enum ChatExecutionType {
    QUERY("query"),
    COMMAND("command"),
    SYNC("sync"),
    RESCHEDULE("reschedule");

    private final String wireValue;

    ChatExecutionType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
