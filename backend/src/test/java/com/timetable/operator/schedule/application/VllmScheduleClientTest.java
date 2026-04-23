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

class VllmScheduleClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void normalizeCallsVllmAndCanonicalizesResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
                    "choices", List.of(Map.of(
                            "message", Map.of("content", content)
                    ))
            ));

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        VllmScheduleClient client = new VllmScheduleClient(
                new AppProperties(
                        "http://localhost:3000",
                        null,
                        null,
                        null,
                        new AppProperties.AiProperties(
                                true,
                                "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                                "test-token",
                                "google/gemma-4-E2B-it",
                                768,
                                0.0,
                                5
                        )
                ),
                objectMapper,
                WebClient.builder().build()
        );

        List<VllmScheduleClient.ImportedScheduleBlock> blocks = client.normalize("월요일 오전 딥워크");

        assertThat(blocks).hasSize(1);
        assertEquals(DayOfWeek.MONDAY, blocks.getFirst().dayOfWeek());
        assertEquals(LocalTime.of(9, 0), blocks.getFirst().startTime());
        assertEquals(LocalTime.of(10, 30), blocks.getFirst().endTime());
        assertEquals("딥 워크", blocks.getFirst().activity());
        assertEquals("WORK", blocks.getFirst().category());
        assertEquals("핵심 과제", blocks.getFirst().note());
        assertEquals("Bearer test-token", authorizationHeader.get());

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("google/gemma-4-E2B-it", body.path("model").asText());
        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("response_format").path("json_schema").path("schema").path("properties").path("blocks"))
                .isNotNull();
    }
}
