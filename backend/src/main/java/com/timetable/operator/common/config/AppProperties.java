package com.timetable.operator.common.config;

import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendBaseUrl,
        AuthProperties auth,
        CalendarProperties calendar,
        ScheduleProperties schedule,
        AiProperties ai
) {
    public record AuthProperties(
            String defaultUserEmail,
            String defaultUserName,
            String googleClientId,
            String googleClientSecret,
            List<String> googleScopes
    ) {
    }

    public record CalendarProperties(
            int syncPastDays,
            int syncFutureDays
    ) {
    }

    public record ScheduleProperties(
            int defaultBufferMinutes,
            int defaultOvertimeTriggerMinutes,
            int defaultOpenGapTriggerMinutes,
            LocalTime defaultQuietHoursStart,
            LocalTime defaultQuietHoursEnd
    ) {
    }

    public record AiProperties(
            boolean enabled,
            String pythonExecutable,
            String gemmaScriptPath,
            int timeoutSeconds
    ) {
    }

    public boolean googleOauthEnabled() {
        return auth != null
                && StringUtils.hasText(auth.googleClientId())
                && StringUtils.hasText(auth.googleClientSecret());
    }

    public List<String> googleOauthScopes() {
        if (auth == null || auth.googleScopes() == null || auth.googleScopes().isEmpty()) {
            return List.of("openid", "profile", "email", "https://www.googleapis.com/auth/calendar.readonly");
        }
        return auth.googleScopes();
    }
}
