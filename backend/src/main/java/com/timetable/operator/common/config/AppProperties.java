package com.timetable.operator.common.config;

import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String releaseMode,
        String frontendBaseUrl,
        AuthProperties auth,
        CalendarProperties calendar,
        ScheduleProperties schedule,
        AiProperties ai,
        EncryptionProperties encryption
) {
    @ConstructorBinding
    public AppProperties {
        if (releaseMode == null || releaseMode.isBlank()) {
            releaseMode = "local";
        }
    }

    public AppProperties(
            String releaseMode,
            String frontendBaseUrl,
            AuthProperties auth,
            CalendarProperties calendar,
            ScheduleProperties schedule,
            AiProperties ai
    ) {
        this(releaseMode, frontendBaseUrl, auth, calendar, schedule, ai, new EncryptionProperties(null));
    }

    public AppProperties(
            String frontendBaseUrl,
            AuthProperties auth,
            CalendarProperties calendar,
            ScheduleProperties schedule,
            AiProperties ai
    ) {
        this("local", frontendBaseUrl, auth, calendar, schedule, ai, new EncryptionProperties(null));
    }

    public record AuthProperties(
            String defaultUserEmail,
            String defaultUserName,
            String googleClientId,
            String googleClientSecret,
            String googleCredentialsFile,
            String googleTokenUrl,
            List<String> googleScopes,
            boolean mockLoginEnabled
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
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            double temperature,
            int timeoutSeconds
    ) {
    }

    public record EncryptionProperties(String key) {
    }

    public boolean googleOauthEnabled() {
        return auth != null && GoogleOauthCredentialsSupport.hasConfiguredCredentials(
                auth.googleClientId(),
                auth.googleClientSecret(),
                auth.googleCredentialsFile()
        );
    }

    public boolean mockLoginEnabled() {
        return auth != null && auth.mockLoginEnabled();
    }

    public List<String> googleOauthScopes() {
        if (auth == null || auth.googleScopes() == null || auth.googleScopes().isEmpty()) {
            return List.of(
                    "openid",
                    "profile",
                    "email",
                    "https://www.googleapis.com/auth/calendar.events",
                    "https://www.googleapis.com/auth/tasks"
            );
        }
        return auth.googleScopes();
    }
}
