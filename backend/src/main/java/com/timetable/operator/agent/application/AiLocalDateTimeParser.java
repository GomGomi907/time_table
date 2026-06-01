package com.timetable.operator.agent.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

final class AiLocalDateTimeParser {

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Seoul");

    private AiLocalDateTimeParser() {
    }

    static ZoneId resolveUserZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException exception) {
            return FALLBACK_ZONE;
        }
    }

    static Instant parseRequired(String value, ZoneId userZone) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return LocalDateTime.parse(value).atZone(userZone).toInstant();
        }
    }

    static Instant parseNullable(String value, ZoneId userZone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parseRequired(value.trim(), userZone);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
