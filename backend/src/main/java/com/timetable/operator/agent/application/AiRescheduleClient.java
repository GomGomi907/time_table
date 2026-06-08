package com.timetable.operator.agent.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.agent.domain.AgentCommandActionType;
import com.timetable.operator.agent.domain.AgentCommandTargetType;
import com.timetable.operator.agent.domain.StructuredAiCommand;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.agent.application.context.ContextPackage;
import com.timetable.operator.common.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiRescheduleClient implements AiAgentStageClient {

    private static final Set<String> EXECUTABLE_ACTIONS = Set.of(
            AgentCommandActionType.CREATE_EVENT.wireValue(),
            AgentCommandActionType.UPDATE_EVENT.wireValue(),
            AgentCommandActionType.MOVE_EVENT.wireValue(),
            AgentCommandActionType.DELETE_EVENT.wireValue(),
            AgentCommandActionType.RECOMMEND_TASK.wireValue(),
            AgentCommandActionType.EXPLAIN_ONLY.wireValue()
    );
    private static final String AI_DISABLED_MESSAGE = "일정 조정 기능이 꺼져 있습니다.";
    private static final String AI_REQUEST_FAILED_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String AI_RESPONSE_EMPTY_MESSAGE = "변경 내용을 읽지 못했습니다.";

    private static final String SYSTEM_PROMPT = """
            You are an AI schedule adjustment planner for a Korean time-table app.

            Input contract:
            - The user payload is JSON with userRequest, generatedAt, timezone, and context.
            - context.weeklyBlocks are repeating weekly schedule blocks.
            - context.events are dated calendar events.
            - context.tasks are actionable tasks.
            - context.messageHistory contains compact recent user/assistant turns ordered oldest-to-newest, including rejected suggestions when available so you do not repeat refused ideas.
            - context.availabilityWindows is included only when the request likely needs empty time-slot reasoning; prefer these windows for new dated events when present.
            - Use only ids included in context for update, move, and delete. This id rule does not apply to create commands.
            - Create commands are independent new objects: create_event/recommend_task MUST use target_id null/targetId null and must not require an existing id.
            - Treat Korean relative dates/times from userRequest in the provided timezone.
            - Act like a product-grade assistant, not a form validator. When intent is clear enough, draft a safe proposal with explicit assumptions instead of asking for every missing field.
            - Default assumptions for create_event when title/activity and a start time are clear:
              * missing date -> today in the user's timezone if still plausible, otherwise the next plausible day;
              * missing duration/end time -> lunch/meal/점심/밥 60 minutes, meeting/회의/미팅 60 minutes, quick/잠깐/짧게/brief 15 minutes, commute/출근/퇴근 45 minutes, exercise/운동 60 minutes, otherwise 60 minutes;
              * include these assumptions in explanation and command reason.
            - Prefer small, directly executable commands over broad plans.
            - If the latest userRequest is a short follow-up, resolve it against context.messageHistory before asking again.
            - If the user request is random, vague, not schedule/task related, lacks title/activity plus any usable time signal after using messageHistory, or asks for destructive deletion without one clear target, return explain_only with requires_confirmation=false instead of guessing.

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
                    "category": "optional WORK | LIFE | HEALTH | TRANSIT | GROWTH | HOBBY | SLEEP | ADMIN",
                    "note": "optional note",
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
            - Never require or invent an id for creation. For create_event, target_type must be "event" and target_id must be null.
            - Existing weekly schedule blocks are applied through action_type *_event with target_type "event" and the block id.
            - Existing canonical events use target_type "event"; tasks use target_type "task".
            - To add a weekly schedule block, use action_type create_event, target_type event, target_id null, and include dayOfWeek, startTime, endTime, activity, and category.
            - Use ISO-8601 UTC instants for canonical event/task dates.
            - category must be one of WORK, LIFE, HEALTH, TRANSIT, GROWTH, HOBBY, SLEEP, ADMIN. Use LIFE only when the user gives no better clue.
            - Never invent external Google ids.
            - If no safe executable change exists, return one explain_only command with requires_confirmation=false.
            - Return compact, complete JSON. Do not truncate string fields. Do not include markdown, comments, or prose outside JSON.
            - Output JSON only. No markdown.
            """;

    private static final String INTERPRETATION_PROMPT = """
            You are the first stage of a Korean schedule/task command agent.

            Read the provided userRequest and context. Return JSON only.
            Decide whether the request is specific enough to draft executable commands.
            Prefer asking one short clarification when the request is vague.

            Input contract:
            - userRequest is the exact Korean text typed by the user.
            - generatedAt is the server timestamp for this request.
            - timezone is the user's timezone. Interpret relative Korean dates/times in this timezone.
            - context.weeklyBlocks are recurring weekly blocks, context.events are dated events, and context.tasks are tasks.
            - context.messageHistory is compact recent conversation history ordered oldest-to-newest; rejected entries mean the user refused that idea and should not be repeated blindly.
            - context.availabilityWindows is server-computed empty schedule time and may be omitted for low-value requests; use it to understand likely insertion points only when present.
            - Existing ids must be copied exactly from context for update/move/delete. Never invent targetId.
            - Create actions do not need existing ids. For action create with targetType event/task, targetId must be null and confidence may be high when title/activity plus time intent are clear.
            - For create event with title/activity and a concrete clock time but missing date or end time, do not mark it unrepairable. Keep the parsed title/start time, set repairable=true, and allow drafting with assistant defaults.
            - Follow-up example: if messageHistory shows the assistant asked for an end time for "오늘 12시에 점심약속" and latest userRequest says "12시라고", preserve the original date/title and treat the latest text as a correction/clarification; draft a safe 60-minute lunch unless the user clearly says otherwise.
            - Leave/vacation/연차/휴가 means the user is unavailable from work. Inspect today/tomorrow or the requested range for work/commute blocks and propose cancelling/removing those; ask one concise follow-up only for non-work personal plans.

            Decision rules:
            - For create event/task, first combine the latest userRequest with messageHistory. Require a clear title/activity and either a concrete date/time, an availabilityWindow match when provided, or enough weekly-block information.
            - For update/move/delete, require exactly one matching existing event/task/block from context.
            - For random text, greetings, explanations, or non-schedule requests, use action explain_only, targetType none, confidence <= 0.4.
            - For a plausible schedule request missing only defaultable date/end/duration, keep confidence high enough for drafting when title and start time are clear. For missing title, start time, or destructive target identity, ask one short Korean clarification.
            - Do not force executable confidence. Use confidence >= 0.78 only when action, target type, and required fields are explicit.

            Required action values: create, update, move, delete, request_reschedule, run_sync, explain_only.
            Required targetType values: event, task, none.
            For update/move/delete, include an existing targetId only if the request clearly identifies exactly one item from context.
            For create, targetId must be null; include title/activity and explicit time/date fields when present.
            Confidence is 0.0..1.0. Use >=0.78 only when action, target, time, and intent are explicit.

            Return:
            {
              "action": "create|update|move|delete|request_reschedule|run_sync|explain_only",
              "targetType": "event|task|none",
              "targetId": "id or null",
              "title": "title/activity or null",
              "dayOfWeek": "MONDAY..SUNDAY or null",
              "startTime": "HH:MM or null",
              "endTime": "HH:MM or null",
              "startAt": "ISO-8601 instant or null",
              "endAt": "ISO-8601 instant or null",
              "suggestedShiftMinutes": "integer or null",
              "missingFields": ["most important missing fields"],
              "ambiguousFields": ["ambiguous fields"],
              "confidence": 0.0,
              "repairable": true,
              "clarificationQuestion": "one short Korean question"
            }
            Return a complete compact JSON object only. No markdown. No trailing explanation.
            """;

    private static final String INTERPRETATION_SCHEMA = """
            {
              "type": "object",
              "required": ["action", "targetType", "targetId", "title", "dayOfWeek", "startTime", "endTime", "startAt", "endAt", "suggestedShiftMinutes", "missingFields", "ambiguousFields", "confidence", "repairable", "clarificationQuestion"],
              "properties": {
                "action": {"type": "string"},
                "targetType": {"type": "string"},
                "targetId": {"type": "string", "nullable": true},
                "title": {"type": "string", "nullable": true},
                "dayOfWeek": {"type": "string", "nullable": true},
                "startTime": {"type": "string", "nullable": true},
                "endTime": {"type": "string", "nullable": true},
                "startAt": {"type": "string", "nullable": true},
                "endAt": {"type": "string", "nullable": true},
                "suggestedShiftMinutes": {"type": "integer", "nullable": true},
                "missingFields": {"type": "array", "items": {"type": "string"}},
                "ambiguousFields": {"type": "array", "items": {"type": "string"}},
                "confidence": {"type": "number"},
                "repairable": {"type": "boolean"},
                "clarificationQuestion": {"type": "string"}
              }
            }
            """;

    private static final String REPAIR_PROMPT = """
            You are the repair stage of a Korean schedule/task command agent.

            You receive the original context, the interpretation, a failed command batch, and the backend failure reason.
            Return one corrected command batch using the exact command schema.
            Do not invent ids. Do not guess missing user information. If repair is impossible, return explain_only.
            Preserve the user's original intent from userRequest. Fix only the invalid or mismatched fields.
            Return compact, complete JSON only.
            Output JSON only.
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
                          "category": {
                            "type": "string",
                            "nullable": true,
                            "enum": ["WORK", "LIFE", "HEALTH", "TRANSIT", "GROWTH", "HOBBY", "SLEEP", "ADMIN"]
                          },
                          "note": {"type": "string", "nullable": true},
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

    @Override
    public AiAgentInterpretation interpret(AiAgentRequest request) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException(AI_DISABLED_MESSAGE);
        }
        try {
            String content = extractGeneratedText(callGeminiGenerateContent(buildRequest(
                    INTERPRETATION_PROMPT,
                    objectMapper.writeValueAsString(promptPayload(request)),
                    INTERPRETATION_SCHEMA
            )));
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("요청 내용을 읽지 못했습니다.");
            }
            return objectMapper.readValue(normalizeJsonObject(content), AiAgentInterpretation.class);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Failed to call Gemini interpretation stage.", exception);
            throw new IllegalStateException("요청 내용을 파악하지 못했습니다.");
        }
    }

    @Override
    public StructuredAiCommandBatch draft(AiAgentRequest request, AiAgentInterpretation interpretation) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException(AI_DISABLED_MESSAGE);
        }
        try {
            DraftRequest draftRequest = new DraftRequest(promptPayload(request), interpretation);
            String content = extractGeneratedText(callGeminiGenerateContent(buildRequest(
                    SYSTEM_PROMPT,
                    objectMapper.writeValueAsString(draftRequest),
                    RESPONSE_SCHEMA
            )));
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("변경 내용을 읽지 못했습니다.");
            }
            return canonicalize(content);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Failed to call Gemini draft stage.", exception);
            throw new IllegalStateException("변경 내용을 준비하지 못했습니다.");
        }
    }

    @Override
    public StructuredAiCommandBatch repair(
            AiAgentRequest request,
            AiAgentInterpretation interpretation,
            StructuredAiCommandBatch failedBatch,
            AiRequestProposalMatchService.MatchResult failure
    ) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException(AI_DISABLED_MESSAGE);
        }
        try {
            RepairRequest repairRequest = new RepairRequest(promptPayload(request), interpretation, failedBatch, failure);
            String content = extractGeneratedText(callGeminiGenerateContent(buildRequest(
                    REPAIR_PROMPT,
                    objectMapper.writeValueAsString(repairRequest),
                    RESPONSE_SCHEMA
            )));
            if (content == null || content.isBlank()) {
                throw new IllegalStateException(AI_RESPONSE_EMPTY_MESSAGE);
            }
            return canonicalize(content);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Failed to call Gemini repair stage.", exception);
            throw new IllegalStateException(AI_REQUEST_FAILED_MESSAGE);
        }
    }

    public StructuredAiCommandBatch suggest(RescheduleAiContext context) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException(AI_DISABLED_MESSAGE);
        }

        try {
            GeminiGenerateContentResponse response = callGeminiGenerateContent(buildRequest(context));

            String content = extractGeneratedText(response);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException(AI_RESPONSE_EMPTY_MESSAGE);
            }
            return canonicalize(content);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Failed to call Gemini reschedule planner.", exception);
            throw new IllegalStateException(AI_REQUEST_FAILED_MESSAGE);
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
            throw new IllegalStateException(providerFailureMessage(statusCode, responseBody));
        }
        return objectMapper.readValue(responseBody, GeminiGenerateContentResponse.class);
    }

    private String providerFailureMessage(int statusCode, String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        String normalizedBody = body.toLowerCase();
        if (statusCode == 429
                || normalizedBody.contains("resource_exhausted")
                || normalizedBody.contains("prepayment credits")
                || normalizedBody.contains("quota")) {
            return "사용량 한도가 소진되어 요청을 처리하지 못했습니다.";
        }
        return AI_REQUEST_FAILED_MESSAGE;
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

    private AgentPromptPayload promptPayload(AiAgentRequest request) {
        String timezone = request.context() == null || request.context().user() == null
                ? null
                : request.context().user().timezone();
        if ((timezone == null || timezone.isBlank()) && request.user() != null) {
            timezone = request.user().getTimezone();
        }
        String userId = request.user() == null || request.user().getId() == null
                ? null
                : request.user().getId().toString();
        return new AgentPromptPayload(
                request.reason(),
                Instant.now().toString(),
                blankToDefault(timezone, "Asia/Seoul"),
                userId,
                request.context()
        );
    }

    private GeminiGenerateContentRequest buildRequest(RescheduleAiContext context) {
        String userPayload;
        try {
            userPayload = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("요청 정보를 준비하지 못했습니다.", exception);
        }
        return buildRequest(SYSTEM_PROMPT, userPayload, RESPONSE_SCHEMA);
    }

    private GeminiGenerateContentRequest buildRequest(String systemPrompt, String userPayload, String responseSchema) {
        return new GeminiGenerateContentRequest(
                new GeminiContent(List.of(new GeminiPart(systemPrompt))),
                List.of(new GeminiContent("user", List.of(new GeminiPart(userPayload)))),
                new GeminiGenerationConfig(
                        appProperties.ai().maxTokens(),
                        appProperties.ai().temperature(),
                        MediaType.APPLICATION_JSON_VALUE,
                        parseSchema(responseSchema)
                )
        );
    }

    private JsonNode parseSchema(String schema) {
        try {
            return objectMapper.readTree(schema);
        } catch (JsonProcessingException exception) {
            log.error("Failed to parse Gemini response schema.", exception);
            throw new IllegalStateException("요청을 준비하지 못했습니다.", exception);
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
        String generatedText = content.parts().stream()
                .map(GeminiPart::text)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining());
        if (generatedText.isBlank() && response.candidates().getFirst().finishReason() != null
                && !response.candidates().getFirst().finishReason().isBlank()) {
            throw new IllegalStateException("Gemini returned no text. finishReason="
                    + response.candidates().getFirst().finishReason());
        }
        return generatedText.isBlank() ? null : generatedText;
    }

    private String normalizeJsonObject(String rawContent) {
        if (rawContent == null) {
            throw new IllegalArgumentException("AI response body is null.");
        }
        String content = rawContent.trim();
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```[a-zA-Z]*\\R?", "")
                    .replaceFirst("\\R?```$", "")
                    .trim();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start && (start > 0 || end < content.length() - 1)) {
            content = content.substring(start, end + 1).trim();
        }
        return content;
    }

    private StructuredAiCommandBatch canonicalize(String rawContent) {
        try {
            StructuredAiCommandBatch batch = objectMapper.readValue(normalizeJsonObject(rawContent), StructuredAiCommandBatch.class);
            if (batch == null) {
                throw new IllegalArgumentException("AI response body is null.");
            }
            String summary = blankToDefault(batch.summary(), "일정 변경 제안");
            String explanation = blankToDefault(batch.explanation(), "현재 일정과 요청을 기준으로 변경 내용을 준비했습니다.");
            List<StructuredAiCommand> commands = batch.commands() == null ? List.of() : batch.commands().stream()
                    .map(this::canonicalizeCommand)
                    .toList();
            if (commands.isEmpty()) {
                commands = List.of(new StructuredAiCommand(
                        AgentCommandActionType.EXPLAIN_ONLY.wireValue(),
                        AgentCommandTargetType.NONE.wireValue(),
                        null,
                        Map.of("summary", "반영할 변경이 없습니다."),
                        "반영할 변경이 없습니다.",
                        false
                ));
            }
            return new StructuredAiCommandBatch(summary, explanation, commands);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.error("Failed to parse AI reschedule response.", exception);
            throw new IllegalStateException(AI_RESPONSE_EMPTY_MESSAGE);
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
                blankToDefault(command.reason(), "일정 변경 제안"),
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
            List<TaskContext> tasks,
            List<MessageHistoryContext> messageHistory,
            List<AvailabilityWindowContext> availabilityWindows,
            ContextPackage contextPackage
    ) {
        public RescheduleAiContext(
                UserContext user,
                RequestContext request,
                List<ScheduleBlockContext> weeklyBlocks,
                List<EventContext> events,
                List<TaskContext> tasks,
                List<MessageHistoryContext> messageHistory,
                List<AvailabilityWindowContext> availabilityWindows
        ) {
            this(user, request, weeklyBlocks, events, tasks, messageHistory, availabilityWindows, null);
        }

        public RescheduleAiContext(
                UserContext user,
                RequestContext request,
                List<ScheduleBlockContext> weeklyBlocks,
                List<EventContext> events,
                List<TaskContext> tasks
        ) {
            this(user, request, weeklyBlocks, events, tasks, List.of(), List.of());
        }
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
            String targetRangeEnd,
            String resolvedRangeStart,
            String resolvedRangeEnd
    ) {
        public RequestContext(
                String triggerType,
                String reason,
                String targetRangeStart,
                String targetRangeEnd
        ) {
            this(triggerType, reason, targetRangeStart, targetRangeEnd, targetRangeStart, targetRangeEnd);
        }
    }

    public record MessageHistoryContext(
            String createdAt,
            String status,
            String userRequest,
            String assistantSummary,
            String assistantExplanation
    ) {
    }

    public record AvailabilityWindowContext(
            String startAt,
            String endAt,
            String localLabel,
            long durationMinutes,
            String source
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

    private record RepairRequest(
            AgentPromptPayload request,
            AiAgentInterpretation interpretation,
            StructuredAiCommandBatch failedBatch,
            AiRequestProposalMatchService.MatchResult failure
    ) {
    }

    private record DraftRequest(
            AgentPromptPayload request,
            AiAgentInterpretation interpretation
    ) {
    }

    private record AgentPromptPayload(
            String userRequest,
            String generatedAt,
            String timezone,
            String userId,
            RescheduleAiContext context
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
            GeminiContent content,
            String finishReason
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
