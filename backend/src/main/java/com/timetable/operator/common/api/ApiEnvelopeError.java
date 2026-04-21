package com.timetable.operator.common.api;

public record ApiEnvelopeError(
        String code,
        String message
) {
}
