package com.timetable.operator.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.config.GoogleOauthCredentials;
import com.timetable.operator.common.config.GoogleOauthCredentialsSupport;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAccessTokenService {

    private static final Duration REFRESH_SKEW = Duration.ofMinutes(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final CalendarConnectionRepository calendarConnectionRepository;

    @Transactional
    public String validAccessToken(CalendarConnection connection) {
        if (hasUsableAccessToken(connection)) {
            return connection.getAccessToken();
        }
        if (connection.getRefreshToken() == null || connection.getRefreshToken().isBlank()) {
            markRefreshFailure(connection, "Google refresh token is missing. Reconnect Google with offline access.");
            throw new IllegalStateException("Google refresh token is missing. Reconnect Google before syncing.");
        }

        try {
            JsonNode response = refresh(connection.getRefreshToken());
            String accessToken = text(response, "access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Google token endpoint did not return access_token.");
            }

            long expiresIn = Math.max(response == null ? 3600L : response.path("expires_in").asLong(3600L), 60L);
            connection.setAccessToken(accessToken);
            connection.setTokenExpiresAt(Instant.now().plusSeconds(expiresIn));
            String grantedScopes = text(response, "scope");
            if (grantedScopes != null) {
                connection.setGrantedScopes(grantedScopes);
            }
            connection.setStatus(CalendarConnectionStatus.CONNECTED);
            connection.setCapabilityError(null);
            calendarConnectionRepository.save(connection);
            return accessToken;
        } catch (RuntimeException exception) {
            markRefreshFailure(connection, exception.getMessage());
            throw new IllegalStateException("Google access token refresh failed. Reconnect Google or retry later.", exception);
        }
    }

    private boolean hasUsableAccessToken(CalendarConnection connection) {
        if (connection.getAccessToken() == null || connection.getAccessToken().isBlank()) {
            return false;
        }
        Instant expiresAt = connection.getTokenExpiresAt();
        return expiresAt == null || expiresAt.isAfter(Instant.now().plus(REFRESH_SKEW));
    }

    private JsonNode refresh(String refreshToken) {
        GoogleOauthCredentials credentials = GoogleOauthCredentialsSupport.resolve(
                appProperties.auth().googleClientId(),
                appProperties.auth().googleClientSecret(),
                appProperties.auth().googleCredentialsFile(),
                objectMapper
        );

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");

        return webClientBuilder.build()
                .post()
                .uri(resolveTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("empty error body")
                        .flatMap(body -> {
                            log.warn("Google token refresh failed with body: {}", body);
                            return Mono.error(new IllegalStateException("Google token endpoint returned an error."));
                        }))
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
    }

    private String resolveTokenUrl() {
        String tokenUrl = appProperties.auth() == null ? null : appProperties.auth().googleTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            return "https://oauth2.googleapis.com/token";
        }
        return tokenUrl.trim();
    }

    private void markRefreshFailure(CalendarConnection connection, String message) {
        connection.setStatus(CalendarConnectionStatus.DEGRADED);
        connection.setCapabilityError(message == null || message.isBlank()
                ? "Google token refresh failed."
                : message);
        calendarConnectionRepository.save(connection);
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode() || node.path(fieldName).isNull()) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
