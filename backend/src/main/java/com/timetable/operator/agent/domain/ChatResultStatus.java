package com.timetable.operator.agent.domain;

public enum ChatResultStatus {
    NORMALIZED("normalized"),
    SYNC_REQUESTED("sync_requested"),
    SUGGESTION_CREATED("suggestion_created"),
    PRIORITY_PROPOSAL_CREATED("priority_proposal_created"),
    REVERTED("reverted"),
    REQUIRES_CONFIRMATION("requires_confirmation"),
    NO_OPERATION("no_operation"),
    FAILED("failed");

    private final String wireValue;

    ChatResultStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
