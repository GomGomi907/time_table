package com.timetable.operator.common.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static <T> ResponseEntity<ApiEnvelope<T>> ok(T data) {
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    public static <T> ResponseEntity<ApiEnvelope<T>> ok(T data, Map<String, Object> meta) {
        return ResponseEntity.ok(ApiEnvelope.ok(data, meta));
    }
}
