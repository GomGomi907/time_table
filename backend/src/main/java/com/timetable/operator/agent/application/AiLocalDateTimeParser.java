package com.timetable.operator.agent.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

public final class AiLocalDateTimeParser {

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Seoul");

    private AiLocalDateTimeParser() {
    }

    public static ZoneId resolveUserZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException exception) {
            return FALLBACK_ZONE;
        }
    }

    public static Instant parseRequired(String value, ZoneId userZone) {
        if (value == null || value.isBlank()) {
            throw new AiDateTimeFormatException("AI datetime value is required.");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException instantException) {
            try {
                return LocalDateTime.parse(value.trim()).atZone(userZone).toInstant();
            } catch (DateTimeParseException localException) {
                throw new AiDateTimeFormatException("AI datetime must be ISO-8601 instant or local datetime: " + value, localException);
            }
        }
    }

    public static Instant parseNullable(String value, ZoneId userZone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseRequired(value.trim(), userZone);
    }
}
