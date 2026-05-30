package com.timetable.operator.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.auth.domain.AppUser;
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
        assertEquals(2048, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("AI schedule adjustment planner")
                .contains("To add a weekly schedule block")
                .contains("category");
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
        assertThat(body.path("generationConfig").path("responseSchema")
                .path("properties").path("commands").path("items").path("properties").path("payload")
                .path("properties").path("category").path("enum"))
                .hasSize(8);
    }

    @Test
    void interpretSendsUserRequestTimezoneAndContextInPromptPayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = """
                    {"action":"create","targetType":"event","targetId":null,"title":"테스트 일정","dayOfWeek":null,"startTime":null,"endTime":null,"startAt":"2026-05-31T09:00:00Z","endAt":"2026-05-31T10:00:00Z","suggestedShiftMinutes":null,"missingFields":[],"ambiguousFields":[],"confidence":0.9,"repairable":true,"clarificationQuestion":"진행할까요?"}
                    """;
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

        AppUser user = new AppUser();
        user.setTimezone("Asia/Seoul");
        AiRescheduleClient client = new AiRescheduleClient(
                appProperties("http://127.0.0.1:%d/v1beta".formatted(server.getAddress().getPort())),
                objectMapper
        );

        AiAgentInterpretation interpretation = client.interpret(new AiAgentRequest(
                user,
                "오늘 오후 6시에 테스트 일정 추가해줘",
                new AiRescheduleClient.RescheduleAiContext(
                        new AiRescheduleClient.UserContext("user-1", "Asia/Seoul"),
                        new AiRescheduleClient.RequestContext("manual_request", "오늘 오후 6시에 테스트 일정 추가해줘", null, null),
                        List.of(),
                        List.of(),
                        List.of()
                )
        ));

        assertEquals("create", interpretation.action());
        JsonNode body = objectMapper.readTree(requestBody.get());
        String promptPayload = body.path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(promptPayload)
                .contains("userRequest")
                .contains("오늘 오후 6시에 테스트 일정 추가해줘")
                .contains("generatedAt")
                .contains("timezone")
                .contains("Asia/Seoul")
                .contains("context");
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText())
                .contains("userRequest is the exact Korean text")
                .contains("For random text");
    }

    @Test
    void suggestCombinesSplitGeminiTextPartsBeforeParsingJson() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            String content = objectMapper.writeValueAsString(Map.of(
                    "summary", "일정을 추가합니다",
                    "explanation", "요청한 시간에 새 일정을 제안합니다.",
                    "commands", List.of(Map.of(
                            "action_type", "create_event",
                            "target_type", "event",
                            "target_id", "",
                            "payload", Map.of(
                                    "title", "테스트 일정",
                                    "startAt", "2026-05-31T09:00:00Z",
                                    "endAt", "2026-05-31T10:00:00Z",
                                    "category", "WORK"
                            ),
                            "reason", "사용자 요청",
                            "requires_confirmation", true
                    ))
            ));
            int splitAt = content.indexOf("테스트");
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "candidates", List.of(Map.of("content", Map.of(
                            "parts", List.of(
                                    Map.of("text", content.substring(0, splitAt)),
                                    Map.of("text", content.substring(splitAt))
                            )
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
                new AiRescheduleClient.RequestContext("manual_request", "테스트 일정을 추가해줘", null, null),
                List.of(),
                List.of(),
                List.of()
        ));

        assertEquals("일정을 추가합니다", batch.summary());
        assertThat(batch.commands()).hasSize(1);
        assertEquals("create_event", batch.commands().getFirst().actionType());
        assertEquals("테스트 일정", batch.commands().getFirst().payload().get("title"));
    }

    private AppProperties appProperties(String baseUrl) {
        return new AppProperties(
                "http://localhost:3000",
                null,
                null,
                null,
                new AppProperties.AiProperties(true, baseUrl, "gemini-token", "gemini-2.5-flash", 2048, 0.0, 5)
        );
    }
}

