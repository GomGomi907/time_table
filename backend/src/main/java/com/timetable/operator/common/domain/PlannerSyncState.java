package com.timetable.operator.common.domain;

public enum PlannerSyncState {
    IMPORTED,
    FORKED,
    DETACHED,
    LOCAL_ONLY,
    DIRTY_PENDING_WRITE,
    WRITE_IN_FLIGHT,
    SYNCED,
    WRITE_FAILED_RETRYABLE,
    WRITE_FAILED_NEEDS_RECONNECT,
    CONFLICT_PENDING
}
