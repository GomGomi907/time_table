package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.common.config.AppProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiRescheduleClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void suggestCallsGeminiGenerateContentEndpointAndParsesCommandBatch() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String content = objectMapper.writeValueAsString(Map.of(
                    "summary", "오전 회의를 30분 뒤로 조정",
                    "explanation", "겹치는 업무를 피하기 위해 회의 시간을 뒤로 옮깁니다.",
                    "commands", List.of(Map.of(
                            "action_type", "move_event",
                            "target_type", "event",
                            "target_id", "event-1",
                            "payload", Map.of("suggestedShiftMinutes", 30),
                            "reason", "업무 집중 시간 확보",
                            "requires_confirmation", true
                    ))
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

        AiRescheduleClient client = new AiRescheduleClient(
                appProperties("http://127.0.0.1:%d/v1beta".formatted(server.getAddress().getPort())),
                objectMapper
        );

        StructuredAiCommandBatch batch = client.suggest(new AiRescheduleClient.RescheduleAiContext(
                new AiRescheduleClient.UserContext("user-1", "Asia/Seoul"),
                new AiRescheduleClient.RequestContext("manual_request", "오전 일정이 너무 빡빡함", null, null),
                List.of(new AiRescheduleClient.ScheduleBlockContext("block-1", "MONDAY", "09:00", "10:00", "운동", "HEALTH", null)),
                List.of(new AiRescheduleClient.EventContext("event-1", "회의", null, "WORK", "2026-05-15T01:00:00Z", "2026-05-15T02:00:00Z", "PLANNED", "LOCAL_ONLY")),
                List.of()
        ));

        assertEquals("오전 회의를 30분 뒤로 조정", batch.summary());
        assertThat(batch.commands()).hasSize(1);
        assertEquals("move_event", batch.commands().getFirst().actionType());
        assertEquals("event", batch.commands().getFirst().targetType());
        assertEquals("event-1", batch.commands().getFirst().targetId());
        assertThat(batch.commands().getFirst().requiresConfirmation()).isTrue();
        assertEquals("gemini-token", apiKeyHeader.get());

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("application/json",
                body.path("generationConfig").path("responseMimeType").asText());
        assertEquals(768, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("AI schedule adjustment planner");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText())
                .contains("오전 일정이 너무 빡빡함")
                .contains("event-1");
        assertThat(body.path("generationConfig").path("responseSchema").path("properties").path("commands"))
                .isNotNull();
        assertThat(body.path("generationConfig").path("responseSchema").path("additionalProperties").isMissingNode())
                .isTrue();
        assertThat(body.path("generationConfig").path("responseSchema")
                .path("properties").path("commands").path("items").path("properties").path("target_id").path("nullable").asBoolean())
                .isTrue();
    }

    private AppProperties appProperties(String baseUrl) {
        return new AppProperties(
                "http://localhost:3000",
                null,
                null,
                null,
                new AppProperties.AiProperties(true, baseUrl, "gemini-token", "gemini-2.5-flash", 768, 0.0, 5)
        );
    }
}
