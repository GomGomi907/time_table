package com.timetable.operator.sync.domain;

public enum ProviderWriteState {
    DIRTY_PENDING_WRITE,
    WRITE_IN_FLIGHT,
    SYNCED,
    WRITE_FAILED_RETRYABLE,
    WRITE_FAILED_NEEDS_RECONNECT,
    CONFLICT_PENDING
}
