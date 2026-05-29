package com.timetable.operator.agent.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiRescheduleClient {

    private static final Set<String> EXECUTABLE_ACTIONS = Set.of(
            AgentCommandActionType.CREATE_EVENT.wireValue(),
            AgentCommandActionType.UPDATE_EVENT.wireValue(),
            AgentCommandActionType.MOVE_EVENT.wireValue(),
            AgentCommandActionType.DELETE_EVENT.wireValue(),
            AgentCommandActionType.RECOMMEND_TASK.wireValue(),
            AgentCommandActionType.EXPLAIN_ONLY.wireValue()
    );

    private static final String SYSTEM_PROMPT = """
            You are an AI schedule adjustment planner for a Korean time-table app.

            Return exactly one JSON object with this shape:
            {
              "summary": "short Korean summary",
              "explanation": "Korean explanation of tradeoffs",
              "commands": [
                {
                  "action_type": "move_event | update_event | create_event | delete_event | recommend_task | explain_only",
                  "target_type": "event | task | none",
                  "target_id": "existing id or null",
                  "payload": {
                    "title": "optional title",
                    "description": "optional description",
                    "startAt": "optional ISO-8601 instant",
                    "endAt": "optional ISO-8601 instant",
                    "suggestedShiftMinutes": "optional integer",
                    "dayOfWeek": "optional MONDAY..SUNDAY for weekly blocks",
                    "startTime": "optional HH:MM",
                    "endTime": "optional HH:MM",
                    "activity": "optional activity",
                    "estimatedMinutes": "optional integer",
                    "priority": "optional 1..5"
                  },
                  "reason": "why this command helps",
                  "requires_confirmation": true
                }
              ]
            }

            Rules:
            - Use only ids present in the provided context for move/update/delete commands.
            - Existing weekly schedule blocks are applied through action_type *_event with target_type "event" and the block id.
            - Existing canonical events use target_type "event"; tasks use target_type "task".
            - Use ISO-8601 UTC instants for canonical event/task dates.
            - Never invent external Google ids.
            - If no safe executable change exists, return one explain_only command with requires_confirmation=false.
            - Output JSON only. No markdown.
            """;

    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "required": ["summary", "explanation", "commands"],
              "properties": {
                "summary": {"type": "string"},
                "explanation": {"type": "string"},
                "commands": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "required": ["action_type", "target_type", "target_id", "payload", "reason", "requires_confirmation"],
                    "properties": {
                      "action_type": {
                        "type": "string",
                        "enum": ["move_event", "update_event", "create_event", "delete_event", "recommend_task", "explain_only"]
                      },
                      "target_type": {
                        "type": "string",
                        "enum": ["event", "task", "none"]
                      },
                      "target_id": {"type": "string", "nullable": true},
                      "payload": {
                        "type": "object",
                        "properties": {
                          "title": {"type": "string", "nullable": true},
                          "description": {"type": "string", "nullable": true},
                          "startAt": {"type": "string", "nullable": true},
                          "endAt": {"type": "string", "nullable": true},
                          "suggestedShiftMinutes": {"type": "integer", "nullable": true},
                          "dayOfWeek": {"type": "string", "nullable": true},
                          "startTime": {"type": "string", "nullable": true},
                          "endTime": {"type": "string", "nullable": true},
                          "activity": {"type": "string", "nullable": true},
                          "estimatedMinutes": {"type": "integer", "nullable": true},
                          "priority": {"type": "integer", "nullable": true}
                        }
                      },
                      "reason": {"type": "string"},
                      "requires_confirmation": {"type": "boolean"}
                    }
                  }
                }
              }
            }
            """;

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public StructuredAiCommandBatch suggest(RescheduleAiContext context) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException("AI reschedule planner is disabled.");
        }

        try {
            GeminiGenerateContentResponse response = callGeminiGenerateContent(buildRequest(context));

            String content = extractGeneratedText(response);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("AI 재조율 응답을 해석하지 못했습니다.");
            }
            return canonicalize(content);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Failed to call Gemini reschedule planner.", exception);
            throw new IllegalStateException("AI 재조율 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private GeminiGenerateContentResponse callGeminiGenerateContent(GeminiGenerateContentRequest requestBody) throws IOException {
        String serializedBody = objectMapper.writeValueAsString(requestBody);
        byte[] payload = serializedBody.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(resolveGenerateContentUri()).toURL().openConnection();
        int timeoutMillis = (int) Duration.ofSeconds(appProperties.ai().timeoutSeconds()).toMillis();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        connection.setRequestProperty("x-goog-api-key", requireApiKey());
        try (var outputStream = connection.getOutputStream()) {
            outputStream.write(payload);
        }

        int statusCode = connection.getResponseCode();
        String responseBody = readBody(statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (statusCode >= 400) {
            log.warn("Gemini reschedule request failed with status {} and body: {}",
                    statusCode,
                    responseBody);
            throw new IllegalStateException("AI 재조율 요청에 실패했습니다.");
        }
        return objectMapper.readValue(responseBody, GeminiGenerateContentResponse.class);
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String resolveGenerateContentUri() {
        String baseUrl = appProperties.ai().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Gemini API base URL is not configured.");
        }
        String model = appProperties.ai().model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Gemini model is not configured.");
        }
        String modelId = model.trim().replaceFirst("^models/", "");
        return baseUrl.replaceAll("/+$", "")
                + "/models/"
                + URLEncoder.encode(modelId, StandardCharsets.UTF_8)
                + ":generateContent";
    }

    private String requireApiKey() {
        String apiKey = appProperties.ai().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        return apiKey.trim();
    }

    private GeminiGenerateContentRequest buildRequest(RescheduleAiContext context) {
        String userPayload;
        try {
            userPayload = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 재조율 컨텍스트를 만들지 못했습니다.", exception);
        }
        return new GeminiGenerateContentRequest(
                new GeminiContent(List.of(new GeminiPart(SYSTEM_PROMPT))),
                List.of(new GeminiContent("user", List.of(new GeminiPart(userPayload)))),
                new GeminiGenerationConfig(
                        appProperties.ai().maxTokens(),
                        appProperties.ai().temperature(),
                        MediaType.APPLICATION_JSON_VALUE,
                        parseSchema()
                )
        );
    }

    private JsonNode parseSchema() {
        try {
            return objectMapper.readTree(RESPONSE_SCHEMA);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 재조율 응답 스키마를 준비하지 못했습니다.", exception);
        }
    }

    private String extractGeneratedText(GeminiGenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        GeminiContent content = response.candidates().getFirst().content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().stream()
                .map(GeminiPart::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    private StructuredAiCommandBatch canonicalize(String rawContent) {
        try {
            StructuredAiCommandBatch batch = objectMapper.readValue(rawContent, StructuredAiCommandBatch.class);
            if (batch == null) {
                throw new IllegalArgumentException("AI response body is null.");
            }
            String summary = blankToDefault(batch.summary(), "AI 재조율 제안");
            String explanation = blankToDefault(batch.explanation(), "AI가 현재 일정과 요청을 기준으로 재조율 명령을 생성했습니다.");
            List<StructuredAiCommand> commands = batch.commands() == null ? List.of() : batch.commands().stream()
                    .map(this::canonicalizeCommand)
                    .toList();
            if (commands.isEmpty()) {
                commands = List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of("summary", "실행 가능한 변경이 없습니다."),
                        "AI returned no commands.",
                        false
                ));
            }
            return new StructuredAiCommandBatch(summary, explanation, commands);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.error("Failed to parse AI reschedule response.", exception);
            throw new IllegalStateException("AI 재조율 응답을 해석하지 못했습니다.");
        }
    }

    private StructuredAiCommand canonicalizeCommand(StructuredAiCommand command) {
        String actionType = AgentCommandActionType.from(command.actionType()).wireValue();
        if (!EXECUTABLE_ACTIONS.contains(actionType)) {
            actionType = AgentCommandActionType.EXPLAIN_ONLY.wireValue();
        }
        String targetType = AgentCommandTargetType.from(command.targetType()).wireValue();
        boolean executable = command.requiresConfirmation()
                && !AgentCommandActionType.EXPLAIN_ONLY.wireValue().equals(actionType);
        return new StructuredAiCommand(
                actionType,
                targetType,
                blankToNull(command.targetId()),
                command.payload() == null ? Map.of() : command.payload(),
                blankToDefault(command.reason(), "AI 일정 재조율 제안"),
                executable
        );
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record RescheduleAiContext(
            UserContext user,
            RequestContext request,
            List<ScheduleBlockContext> weeklyBlocks,
            List<EventContext> events,
            List<TaskContext> tasks
    ) {
    }

    public record UserContext(
            String id,
            String timezone
    ) {
    }

    public record RequestContext(
            String triggerType,
            String reason,
            String targetRangeStart,
            String targetRangeEnd
    ) {
    }

    public record ScheduleBlockContext(
            String id,
            String dayOfWeek,
            String startTime,
            String endTime,
            String activity,
            String category,
            String note
    ) {
    }

    public record EventContext(
            String id,
            String title,
            String description,
            String category,
            String startAt,
            String endAt,
            String status,
            String syncState
    ) {
    }

    public record TaskContext(
            String id,
            String title,
            String description,
            String category,
            String dueDate,
            int estimatedMinutes,
            int actualMinutes,
            short priority,
            String status,
            String syncState
    ) {
    }

    private record GeminiGenerateContentRequest(
            GeminiContent systemInstruction,
            List<GeminiContent> contents,
            GeminiGenerationConfig generationConfig
    ) {
    }

    private record GeminiGenerationConfig(
            int maxOutputTokens,
            double temperature,
            String responseMimeType,
            JsonNode responseSchema
    ) {
    }

    private record GeminiGenerateContentResponse(
            List<GeminiCandidate> candidates
    ) {
    }

    private record GeminiCandidate(
            GeminiContent content
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GeminiContent(
            String role,
            List<GeminiPart> parts
    ) {
        private GeminiContent(List<GeminiPart> parts) {
            this(null, parts);
        }
    }

    private record GeminiPart(
            String text
    ) {
    }
}
