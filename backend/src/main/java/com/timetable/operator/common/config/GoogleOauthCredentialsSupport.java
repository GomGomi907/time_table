package com.timetable.operator.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.util.StringUtils;

public final class GoogleOauthCredentialsSupport {

    private GoogleOauthCredentialsSupport() {
    }

    public static boolean hasConfiguredCredentials(
            String clientId,
            String clientSecret,
            String credentialsFile
    ) {
        if (StringUtils.hasText(normalizeCredential(clientId)) && StringUtils.hasText(normalizeCredential(clientSecret))) {
            return true;
        }

        if (looksLikeEmbeddedOauthCredentials(normalizeCredential(clientId))
                || looksLikeEmbeddedOauthCredentials(normalizeCredential(clientSecret))) {
            return true;
        }

        if (!StringUtils.hasText(credentialsFile)) {
            return false;
        }

        try {
            return Files.isRegularFile(Path.of(credentialsFile));
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    public static GoogleOauthCredentials resolve(
            String clientId,
            String clientSecret,
            String credentialsFile,
            ObjectMapper objectMapper
    ) {
        String normalizedClientId = normalizeCredential(clientId);
        String normalizedClientSecret = normalizeCredential(clientSecret);

        GoogleOauthCredentials embeddedCredentials = resolveEmbeddedCredentials(
                normalizedClientId,
                normalizedClientSecret,
                objectMapper
        );
        if (embeddedCredentials != null) {
            return embeddedCredentials;
        }

        if (StringUtils.hasText(normalizedClientId) && StringUtils.hasText(normalizedClientSecret)) {
            return new GoogleOauthCredentials(normalizedClientId, normalizedClientSecret);
        }

        if (!StringUtils.hasText(credentialsFile)) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일 경로가 설정되지 않았습니다.");
        }

        Path credentialsPath;

        try {
            credentialsPath = Path.of(credentialsFile);
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일 경로가 올바르지 않습니다: " + credentialsFile, exception);
        }

        if (!Files.isRegularFile(credentialsPath)) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일을 찾을 수 없습니다: " + credentialsPath);
        }

        try (InputStream inputStream = Files.newInputStream(credentialsPath)) {
            JsonNode root = objectMapper.readTree(inputStream);
            GoogleOauthCredentials resolvedCredentials = extractCredentials(root);

            if (resolvedCredentials == null) {
                throw new IllegalStateException("Google OAuth 자격 증명 파일에 client_id 또는 client_secret이 없습니다.");
            }

            return resolvedCredentials;
        } catch (IOException exception) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일을 읽지 못했습니다: " + credentialsPath, exception);
        }
    }

    private static String normalizeCredential(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("\uFEFF", "").trim();
        normalized = stripWrappingQuotes(normalized);

        int assignmentIndex = normalized.indexOf('=');
        if (assignmentIndex > 0) {
            String key = normalized.substring(0, assignmentIndex).trim().toUpperCase(Locale.ROOT);
            if (key.matches("[A-Z0-9_.-]+") && key.contains("CLIENT")) {
                normalized = stripWrappingQuotes(normalized.substring(assignmentIndex + 1).trim());
            }
        }
        return normalized;
    }

    private static String stripWrappingQuotes(String value) {
        String normalized = value;
        while (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first != '"' || last != '"') && (first != '\'' || last != '\'')) {
                break;
            }
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static GoogleOauthCredentials resolveEmbeddedCredentials(
            String normalizedClientId,
            String normalizedClientSecret,
            ObjectMapper objectMapper
    ) {
        GoogleOauthCredentials credentialsFromId = parseEmbeddedCredentials(normalizedClientId, objectMapper);
        GoogleOauthCredentials credentialsFromSecret = parseEmbeddedCredentials(normalizedClientSecret, objectMapper);

        GoogleOauthCredentials embeddedCredentials = credentialsFromSecret != null ? credentialsFromSecret : credentialsFromId;
        if (embeddedCredentials == null) {
            return null;
        }

        String explicitClientId = credentialsFromId == null ? normalizedClientId : null;
        String explicitClientSecret = credentialsFromSecret == null ? normalizedClientSecret : null;

        if (StringUtils.hasText(explicitClientId) && !explicitClientId.equals(embeddedCredentials.clientId())) {
            throw new IllegalStateException("Google OAuth client_id와 client_secret JSON의 client_id가 서로 다릅니다.");
        }

        if (StringUtils.hasText(explicitClientSecret)
                && !looksLikeEmbeddedOauthCredentials(explicitClientSecret)
                && !explicitClientSecret.equals(embeddedCredentials.clientSecret())) {
            return new GoogleOauthCredentials(embeddedCredentials.clientId(), explicitClientSecret);
        }

        return embeddedCredentials;
    }

    private static GoogleOauthCredentials parseEmbeddedCredentials(String value, ObjectMapper objectMapper) {
        if (!looksLikeEmbeddedOauthCredentials(value)) {
            return null;
        }

        try {
            return extractCredentials(objectMapper.readTree(value));
        } catch (IOException exception) {
            throw new IllegalStateException("Google OAuth JSON 자격 증명 값을 읽지 못했습니다.", exception);
        }
    }

    private static GoogleOauthCredentials extractCredentials(JsonNode root) {
        JsonNode oauthNode = root.path("web");

        if (oauthNode.isMissingNode() || oauthNode.isNull()) {
            oauthNode = root.path("installed");
        }

        String resolvedClientId = normalizeCredential(oauthNode.path("client_id").asText(null));
        String resolvedClientSecret = normalizeCredential(oauthNode.path("client_secret").asText(null));

        if (!StringUtils.hasText(resolvedClientId) || !StringUtils.hasText(resolvedClientSecret)) {
            return null;
        }

        return new GoogleOauthCredentials(resolvedClientId, resolvedClientSecret);
    }

    private static boolean looksLikeEmbeddedOauthCredentials(String value) {
        return StringUtils.hasText(value)
                && value.stripLeading().startsWith("{")
                && value.contains("client_id")
                && value.contains("client_secret");
    }
}
