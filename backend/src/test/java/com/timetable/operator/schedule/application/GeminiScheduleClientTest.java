package com.timetable.operator.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.timetable.operator.common.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class GeminiScheduleClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void normalizeCallsGeminiAndCanonicalizesResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String content = objectMapper.writeValueAsString(Map.of(
                    "blocks", List.of(
                            Map.of(
                                    "dayOfWeek", "월",
                                    "startTime", "9:00",
                                    "endTime", "10:30",
                                    "activity", "딥 워크",
                                    "category", "job",
                                    "note", "핵심 과제"
                            ),
                            Map.of(
                                    "dayOfWeek", "??",
                                    "startTime", "xx",
                                    "endTime", "11:00",
                                    "activity", "애매한 일정",
                                    "category", "life",
                                    "note", ""
                            )
                    ),
                    "warnings", List.of("원본 경고")
            ));

            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "candidates", List.of(Map.of("content", Map.of(
                            "parts", List.of(Map.of("text", content))
                    )))
            ));

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        GeminiScheduleClient client = new GeminiScheduleClient(
                new AppProperties(
                        "http://localhost:3000",
                        null,
                        null,
                        null,
                        new AppProperties.AiProperties(
                                true,
                                "http://127.0.0.1:%d/v1beta".formatted(server.getAddress().getPort()),
                                "gemini-test-token",
                                "gemini-2.5-flash",
                                768,
                                0.0,
                                5
                        )
                ),
                objectMapper,
                WebClient.builder().build()
        );

        List<GeminiScheduleClient.ImportedScheduleBlock> blocks = client.normalize("월요일 오전 딥워크");

        assertThat(blocks).hasSize(1);
        assertEquals(DayOfWeek.MONDAY, blocks.getFirst().dayOfWeek());
        assertEquals(LocalTime.of(9, 0), blocks.getFirst().startTime());
        assertEquals(LocalTime.of(10, 30), blocks.getFirst().endTime());
        assertEquals("딥 워크", blocks.getFirst().activity());
        assertEquals("WORK", blocks.getFirst().category());
        assertEquals("핵심 과제", blocks.getFirst().note());
        assertEquals("gemini-test-token", apiKeyHeader.get());

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("application/json",
                body.path("generationConfig").path("responseFormat").path("text").path("mimeType").asText());
        assertEquals(768, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("strict JSON");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText())
                .contains("월요일 오전 딥워크");
        assertThat(body.path("generationConfig").path("responseFormat").path("text").path("schema").path("properties").path("blocks"))
                .isNotNull();
    }
}
