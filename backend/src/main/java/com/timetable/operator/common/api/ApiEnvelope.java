package com.timetable.operator.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiEnvelope<T>(
        boolean success,
        T data,
        Map<String, Object> meta,
        ApiEnvelopeError error
) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(true, data, Map.of(), null);
    }

    public static <T> ApiEnvelope<T> ok(T data, Map<String, Object> meta) {
        return new ApiEnvelope<>(true, data, meta == null ? Map.of() : Map.copyOf(meta), null);
    }

    public static <T> ApiEnvelope<T> failure(String code, String message) {
        return new ApiEnvelope<>(false, null, Map.of(), new ApiEnvelopeError(code, message));
    }
}
