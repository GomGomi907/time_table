package com.timetable.operator.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReleaseRuntimeGuard implements InitializingBean {

    private final AppProperties appProperties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        String releaseMode = normalize(appProperties.releaseMode());
        boolean safeRuntimeRequired = environment.getProperty("app.require-safe-runtime", Boolean.class, false);
        boolean explicitReleaseMode = isReleaseMode(releaseMode);
        boolean releaseRuntime = explicitReleaseMode || safeRuntimeRequired;
        List<String> violations = new ArrayList<>();
        if (isCloudRun() && !explicitReleaseMode) {
            violations.add("APP_RELEASE_MODE must be beta or production on Cloud Run");
        }
        if (!releaseRuntime && violations.isEmpty()) {
            return;
        }
        String datasourceUrl = environment.getProperty("spring.datasource.url", "").trim();
        if (isUnsafeReleaseDatasource(datasourceUrl)) {
            violations.add("APP_DB_URL must point to PostgreSQL/Cloud SQL");
        }
        if (appProperties.mockLoginEnabled()) {
            violations.add("mock login must be disabled");
        }
        if (environment.getProperty("app.sync.google.mock-enabled", Boolean.class, false)) {
            violations.add("mock Google sync must be disabled");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            violations.add("H2 console must be disabled");
        }
        if (!appProperties.googleOauthEnabled()) {
            violations.add("Google OAuth credentials are required");
        }
        if (appProperties.ai() != null && appProperties.ai().enabled() && isBlank(appProperties.ai().apiKey())) {
            violations.add("APP_GEMINI_API_KEY is required when AI is enabled");
        }
        if (appProperties.encryption() == null || isBlank(appProperties.encryption().key())) {
            violations.add("APP_ENCRYPTION_KEY is required");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe " + releaseMode + " runtime configuration: " + String.join("; ", violations));
        }
    }

    private boolean isCloudRun() {
        return !isBlank(environment.getProperty("K_SERVICE"));
    }

    private static String normalize(String releaseMode) {
        return releaseMode == null ? "local" : releaseMode.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isReleaseMode(String releaseMode) {
        return "beta".equals(releaseMode) || "production".equals(releaseMode);
    }

    private static boolean isUnsafeReleaseDatasource(String datasourceUrl) {
        String normalized = datasourceUrl == null ? "" : datasourceUrl.trim().toLowerCase();
        return normalized.isBlank() || normalized.startsWith("jdbc:h2:") || normalized.contains(":h2:");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
