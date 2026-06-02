package com.timetable.operator.schedule.domain;

public enum RoutineShadowState {
    NONE,
    SHADOWED_BY_EVENT,
    SHADOWED_BY_TASK,
    SANCTUARY_BLOCKED,
    USER_OVERRIDDEN
}
