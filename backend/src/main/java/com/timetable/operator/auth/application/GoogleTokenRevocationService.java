package com.timetable.operator.auth.application;

import com.timetable.operator.common.config.AppProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTokenRevocationService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;

    public void revokeIfConfigured(String refreshToken, String accessToken) {
        if (!appProperties.googleOauthEnabled()) {
            return;
        }

        String token = firstPresent(refreshToken, accessToken);
        if (token == null) {
            return;
        }

        try {
            webClientBuilder.build()
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("oauth2.googleapis.com")
                            .path("/revoke")
                            .queryParam("token", token)
                            .build())
                    .retrieve()
                    .toBodilessEntity()
                    .block(REQUEST_TIMEOUT);
        } catch (RuntimeException exception) {
            log.warn("Google token revocation failed; clearing local token state anyway.", exception);
        }
    }

    private String firstPresent(String refreshToken, String accessToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            return refreshToken;
        }
        if (accessToken != null && !accessToken.isBlank()) {
            return accessToken;
        }
        return null;
    }
}
