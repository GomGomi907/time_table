package com.timetable.operator.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

final class GoogleOauthCredentialsSupport {

    private GoogleOauthCredentialsSupport() {
    }

    static boolean hasConfiguredCredentials(
            String clientId,
            String clientSecret,
            String credentialsFile
    ) {
        if (StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret)) {
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

    static GoogleOauthCredentials resolve(
            String clientId,
            String clientSecret,
            String credentialsFile,
            ObjectMapper objectMapper
    ) {
        if (StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret)) {
            return new GoogleOauthCredentials(clientId, clientSecret);
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

            String resolvedClientId = oauthNode.path("client_id").asText(null);
            String resolvedClientSecret = oauthNode.path("client_secret").asText(null);

            if (!StringUtils.hasText(resolvedClientId) || !StringUtils.hasText(resolvedClientSecret)) {
                throw new IllegalStateException("Google OAuth 자격 증명 파일에 client_id 또는 client_secret이 없습니다.");
            }

            return new GoogleOauthCredentials(resolvedClientId, resolvedClientSecret);
        } catch (IOException exception) {
            throw new IllegalStateException("Google OAuth 자격 증명 파일을 읽지 못했습니다: " + credentialsPath, exception);
        }
    }
}
