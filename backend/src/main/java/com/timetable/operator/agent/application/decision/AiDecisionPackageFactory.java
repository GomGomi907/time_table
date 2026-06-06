package com.timetable.operator.agent.application.decision;

import com.timetable.operator.agent.domain.AiDecisionPackage;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiDecisionPackageFactory {

    private static final String FALLBACK_TIMEZONE = "Asia/Seoul";

    public AiDecisionPackage fromBatch(StructuredAiCommandBatch batch) {
        return fromBatch(batch, null, null, null, null);
    }

    public AiDecisionPackage fromBatch(
            StructuredAiCommandBatch batch,
            String timezone,
            String scopeStart,
            String scopeEnd,
            String requestKind
    ) {
        String resolvedTimezone = firstNonBlank(timezone, readFirstPayloadString(batch, "timezone"), FALLBACK_TIMEZONE);
        String resolvedScopeStart = firstNonBlank(scopeStart, readFirstPayloadString(batch, "scopeStart"));
        String resolvedScopeEnd = firstNonBlank(scopeEnd, readFirstPayloadString(batch, "scopeEnd"));
        String resolvedRequestKind = firstNonBlank(requestKind, readFirstPayloadString(batch, "requestKind"));
        return AiDecisionPackage.from(batch, resolvedTimezone, resolvedScopeStart, resolvedScopeEnd, resolvedRequestKind);
    }

    private String readFirstPayloadString(StructuredAiCommandBatch batch, String key) {
        if (batch == null || batch.commands() == null || batch.commands().isEmpty()) {
            return null;
        }
        StructuredAiCommand command = batch.commands().getFirst();
        Map<String, Object> payload = command.payload();
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
