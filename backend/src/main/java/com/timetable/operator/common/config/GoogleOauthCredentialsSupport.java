package com.timetable.operator.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
            JsonNode oauthNode = root.path("web");

            if (oauthNode.isMissingNode() || oauthNode.isNull()) {
                oauthNode = root.path("installed");
            }

            String resolvedClientId = normalizeCredential(oauthNode.path("client_id").asText(null));
            String resolvedClientSecret = normalizeCredential(oauthNode.path("client_secret").asText(null));

            if (!StringUtils.hasText(resolvedClientId) || !StringUtils.hasText(resolvedClientSecret)) {
                throw new IllegalStateException("Google OAuth 자격 증명 파일에 client_id 또는 client_secret이 없습니다.");
            }

            return new GoogleOauthCredentials(resolvedClientId, resolvedClientSecret);
        } catch (IOException exception) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일을 읽지 못했습니다: " + credentialsPath, exception);
        }
    }

    private static String normalizeCredential(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return normalized;
    }
}
