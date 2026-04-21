package com.timetable.operator.agent.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record StructuredAiCommand(
        @JsonProperty("action_type") String actionType,
        @JsonProperty("target_type") String targetType,
        @JsonProperty("target_id") String targetId,
        Map<String, Object> payload,
        String reason,
        @JsonProperty("requires_confirmation") boolean requiresConfirmation
) {
}
