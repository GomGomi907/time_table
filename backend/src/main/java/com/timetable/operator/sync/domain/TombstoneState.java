package com.timetable.operator.sync.domain;

public enum TombstoneState {
    NONE,
    REMOTE_DELETED,
    LOCAL_DELETED,
    PURGED
}
