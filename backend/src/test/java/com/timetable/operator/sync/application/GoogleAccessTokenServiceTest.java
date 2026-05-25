package com.timetable.operator.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class GoogleAccessTokenServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void refreshesExpiredAccessTokenWithStoredRefreshToken() throws Exception {
        AtomicReference<String> formBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token", exchange -> {
            formBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "access_token", "fresh-access-token",
                    "expires_in", 3600,
                    "scope", "https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/tasks"
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        CalendarConnectionRepository repository = mock(CalendarConnectionRepository.class);
        GoogleAccessTokenService service = new GoogleAccessTokenService(
                appProperties("http://127.0.0.1:%d/token".formatted(server.getAddress().getPort())),
                objectMapper,
                WebClient.builder(),
                repository
        );
        CalendarConnection connection = new CalendarConnection();
        connection.setAccessToken("expired-token");
        connection.setRefreshToken("refresh-token");
        connection.setTokenExpiresAt(Instant.now().minusSeconds(10));
        connection.setStatus(CalendarConnectionStatus.DEGRADED);

        String accessToken = service.validAccessToken(connection);

        assertThat(accessToken).isEqualTo("fresh-access-token");
        assertThat(connection.getAccessToken()).isEqualTo("fresh-access-token");
        assertThat(connection.getTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3000));
        assertThat(connection.getGrantedScopes()).contains("calendar.events").contains("tasks");
        assertThat(connection.getStatus()).isEqualTo(CalendarConnectionStatus.CONNECTED);
        assertThat(formBody.get())
                .contains("client_id=test-client")
                .contains("client_secret=test-secret")
                .contains("refresh_token=refresh-token")
                .contains("grant_type=refresh_token");
        verify(repository).save(any(CalendarConnection.class));
    }

    private AppProperties appProperties(String tokenUrl) {
        return new AppProperties(
                "http://localhost:3000",
                new AppProperties.AuthProperties(
                        "local@time-table.dev",
                        "Local User",
                        "test-client",
                        "test-secret",
                        "",
                        tokenUrl,
                        java.util.List.of(),
                        false
                ),
                null,
                null,
                new AppProperties.AiProperties(false, "https://generativelanguage.googleapis.com/v1beta", "test", "gemini-2.5-flash", 768, 0.0, 5)
        );
    }
}
