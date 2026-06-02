package com.timetable.operator.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReleaseRuntimeGuardTest {

    @Test
    void localModeAllowsDeveloperFallbacks() {
        AppProperties properties = properties("local", true, true, null, null, null);
        MockEnvironment environment = environment("jdbc:h2:file:./data/timetable", true);

        assertThatCode(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void betaModeAllowsPostgresRealGoogleAndAiKey() {
        AppProperties properties = properties("beta", false, true, "client-id", "client-secret", "gemini-key");
        MockEnvironment environment = environment("jdbc:postgresql://localhost:5432/timetable", false);

        assertThatCode(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void betaModeRejectsUnsafeDatabaseAndMockSwitches() {
        AppProperties properties = properties("beta", true, true, "client-id", "client-secret", "gemini-key");
        MockEnvironment environment = environment("jdbc:h2:mem:timetable", true);

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe beta runtime configuration")
                .hasMessageContaining("APP_DB_URL must point to PostgreSQL/Cloud SQL")
                .hasMessageContaining("mock login must be disabled")
                .hasMessageContaining("mock Google sync must be disabled");
    }

    @Test
    void productionModeRejectsMissingGoogleCredentialsAndAiKey() {
        AppProperties properties = properties("production", false, true, null, null, null);
        MockEnvironment environment = environment("jdbc:postgresql://localhost:5432/timetable", false);

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe production runtime configuration")
                .hasMessageContaining("Google OAuth credentials are required")
                .hasMessageContaining("APP_GEMINI_API_KEY is required when AI is enabled");
    }

    @Test
    void betaModeRejectsEnabledH2ConsoleEvenWithPostgres() {
        AppProperties properties = properties("beta", false, true, "client-id", "client-secret", "gemini-key");
        MockEnvironment environment = environment("jdbc:postgresql://localhost:5432/timetable", false)
                .withProperty("spring.h2.console.enabled", "true");

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe beta runtime configuration")
                .hasMessageContaining("H2 console must be disabled");
    }

    @Test
    void cloudRunRejectsMissingReleaseMode() {
        AppProperties properties = properties("local", false, false, "client-id", "client-secret", null);
        MockEnvironment environment = environment("jdbc:postgresql://localhost:5432/timetable", false)
                .withProperty("K_SERVICE", "timetable");

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe local runtime configuration")
                .hasMessageContaining("APP_RELEASE_MODE must be beta or production on Cloud Run");
    }

    @Test
    void cloudRunRequiresExplicitReleaseModeEvenWhenSafeRuntimeFlagIsEnabled() {
        AppProperties properties = properties("local", false, true, "client-id", "client-secret", "gemini-key");
        MockEnvironment environment = environment("jdbc:postgresql://localhost:5432/timetable", false)
                .withProperty("K_SERVICE", "timetable")
                .withProperty("app.require-safe-runtime", "true");

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe local runtime configuration")
                .hasMessageContaining("APP_RELEASE_MODE must be beta or production on Cloud Run");
    }

    @Test
    void explicitSafeRuntimeFlagEnforcesReleaseChecksOutsideCloudRun() {
        AppProperties properties = properties("local", true, true, "client-id", "client-secret", "gemini-key");
        MockEnvironment environment = environment("jdbc:h2:file:./data/timetable", true)
                .withProperty("app.require-safe-runtime", "true");

        assertThatThrownBy(() -> new ReleaseRuntimeGuard(properties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe local runtime configuration")
                .hasMessageContaining("APP_DB_URL must point to PostgreSQL/Cloud SQL")
                .hasMessageContaining("mock login must be disabled")
                .hasMessageContaining("mock Google sync must be disabled");
    }

    private static MockEnvironment environment(String datasourceUrl, boolean googleMockEnabled) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", datasourceUrl)
                .withProperty("app.sync.google.mock-enabled", Boolean.toString(googleMockEnabled));
    }

    private static AppProperties properties(
            String releaseMode,
            boolean mockLoginEnabled,
            boolean aiEnabled,
            String googleClientId,
            String googleClientSecret,
            String aiApiKey
    ) {
        return new AppProperties(
                releaseMode,
                "http://localhost:3000",
                new AppProperties.AuthProperties(
                        "local@time-table.dev",
                        "Local User",
                        googleClientId,
                        googleClientSecret,
                        null,
                        "https://oauth2.googleapis.com/token",
                        List.of("openid", "profile", "email"),
                        mockLoginEnabled
                ),
                new AppProperties.CalendarProperties(7, 30),
                new AppProperties.ScheduleProperties(10, 15, 30, LocalTime.of(22, 0), LocalTime.of(8, 0)),
                new AppProperties.AiProperties(
                        aiEnabled,
                        "https://generativelanguage.googleapis.com/v1beta",
                        aiApiKey,
                        "gemini-2.5-flash",
                        2048,
                        0.0,
                        8
                ),
                new AppProperties.EncryptionProperties("test-encryption-key")
        );
    }
}
