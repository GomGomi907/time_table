package com.timetable.operator.schedule.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.common.config.AppProperties;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class VllmScheduleClient {
    private static final Set<String> DAY_VALUES = Set.of(
            "MONDAY",
            "TUESDAY",
            "WEDNESDAY",
            "THURSDAY",
            "FRIDAY",
            "SATURDAY",
            "SUNDAY"
    );

    private static final Map<String, String> DAY_ALIASES = Map.ofEntries(
            Map.entry("MON", "MONDAY"),
            Map.entry("MONDAY", "MONDAY"),
            Map.entry("월", "MONDAY"),
            Map.entry("TUE", "TUESDAY"),
            Map.entry("TUESDAY", "TUESDAY"),
            Map.entry("화", "TUESDAY"),
            Map.entry("WED", "WEDNESDAY"),
            Map.entry("WEDNESDAY", "WEDNESDAY"),
            Map.entry("수", "WEDNESDAY"),
            Map.entry("THU", "THURSDAY"),
            Map.entry("THURSDAY", "THURSDAY"),
            Map.entry("목", "THURSDAY"),
            Map.entry("FRI", "FRIDAY"),
            Map.entry("FRIDAY", "FRIDAY"),
            Map.entry("금", "FRIDAY"),
            Map.entry("SAT", "SATURDAY"),
            Map.entry("SATURDAY", "SATURDAY"),
            Map.entry("토", "SATURDAY"),
            Map.entry("SUN", "SUNDAY"),
            Map.entry("SUNDAY", "SUNDAY"),
            Map.entry("일", "SUNDAY")
    );

    private static final Set<String> CATEGORY_VALUES = Set.of(
            "WORK",
            "LIFE",
            "HEALTH",
            "TRANSIT",
            "GROWTH",
            "HOBBY",
            "SLEEP",
            "ADMIN"
    );

    private static final Map<String, String> CATEGORY_ALIASES = Map.ofEntries(
            Map.entry("WORK", "WORK"),
            Map.entry("JOB", "WORK"),
            Map.entry("LIFE", "LIFE"),
            Map.entry("PERSONAL", "LIFE"),
            Map.entry("HEALTH", "HEALTH"),
            Map.entry("EXERCISE", "HEALTH"),
            Map.entry("WORKOUT", "HEALTH"),
            Map.entry("FITNESS", "HEALTH"),
            Map.entry("TRANSIT", "TRANSIT"),
            Map.entry("COMMUTE", "TRANSIT"),
            Map.entry("GROWTH", "GROWTH"),
            Map.entry("STUDY", "GROWTH"),
            Map.entry("LEARNING", "GROWTH"),
            Map.entry("HOBBY", "HOBBY"),
            Map.entry("FUN", "HOBBY"),
            Map.entry("SLEEP", "SLEEP"),
            Map.entry("REST", "SLEEP"),
            Map.entry("ADMIN", "ADMIN")
    );

    private static final String SYSTEM_PROMPT = """
            You convert messy weekly schedule text into strict JSON.

            Return exactly one JSON object with this shape:
            {
              "blocks": [
                {
                  "dayOfWeek": "MONDAY",
                  "startTime": "09:00",
                  "endTime": "10:00",
                  "activity": "string",
                  "category": "WORK",
                  "note": "string or null"
                }
              ],
              "warnings": ["string"]
            }

            Rules:
            - dayOfWeek must be one of MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.
            - category must be one of WORK, LIFE, HEALTH, TRANSIT, GROWTH, HOBBY, SLEEP, ADMIN.
            - startTime and endTime must be HH:MM 24-hour format.
            - Split blocks only when the source clearly implies separate schedule blocks.
            - If day or time is missing or ambiguous, do not guess. Put a warning and omit that block.
            - Prefer faithful normalization over creativity.
            - Output JSON only. No markdown. No explanation.
            """;

    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["blocks", "warnings"],
              "properties": {
                "blocks": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["dayOfWeek", "startTime", "endTime", "activity", "category", "note"],
                    "properties": {
                      "dayOfWeek": {
                        "type": "string",
                        "enum": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
                      },
                      "startTime": {
                        "type": "string",
                        "pattern": "^(?:[01]\\\\d|2[0-3]):[0-5]\\\\d$"
                      },
                      "endTime": {
                        "type": "string",
                        "pattern": "^(?:[01]\\\\d|2[0-3]):[0-5]\\\\d$"
                      },
                      "activity": {
                        "type": "string",
                        "minLength": 1
                      },
                      "category": {
                        "type": "string",
                         "enum": ["WORK", "LIFE", "HEALTH", "TRANSIT", "GROWTH", "HOBBY", "SLEEP", "ADMIN"]
                      },
                      "note": {
                        "type": ["string", "null"]
                      }
                    }
                  }
                },
                "warnings": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                }
              }
            }
            """;

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public List<ImportedScheduleBlock> normalize(String rawText) {
        if (!appProperties.ai().enabled()) {
            throw new IllegalStateException("vLLM schedule normalization is disabled.");
        }
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        try {
            ChatCompletionResponse response = webClient.post()
                    .uri(resolveChatCompletionsUri())
                    .headers(headers -> {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        String apiKey = appProperties.ai().apiKey();
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.setBearerAuth(apiKey.trim());
                        }
                    })
                    .bodyValue(buildRequest(rawText.trim()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("empty error body")
                            .flatMap(body -> {
                                log.warn("vLLM schedule normalization failed with body: {}", body);
                                return Mono.error(new IllegalStateException("AI 시간표 정규화에 실패했습니다. 잠시 후 다시 시도해 주세요."));
                            }))
                    .bodyToMono(ChatCompletionResponse.class)
                    .block(Duration.ofSeconds(appProperties.ai().timeoutSeconds()));

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            AssistantMessage message = response.choices().getFirst().message();
            if (message == null || message.content() == null || message.content().isBlank()) {
                throw new IllegalStateException("AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            ScheduleImportPayload payload = canonicalizePayload(message.content());
            return payload.blocks();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Failed to call vLLM schedule normalizer.", exception);
            throw new IllegalStateException("AI 시간표 정규화 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String resolveChatCompletionsUri() {
        String baseUrl = appProperties.ai().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("vLLM base URL is not configured.");
        }
        return baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }

    private ChatCompletionRequest buildRequest(String rawText) {
        return new ChatCompletionRequest(
                appProperties.ai().model(),
                List.of(
                        new RequestMessage("system", SYSTEM_PROMPT),
                        new RequestMessage("user", rawText)
                ),
                appProperties.ai().maxTokens(),
                appProperties.ai().temperature(),
                new ResponseFormat(
                        "json_schema",
                        new JsonSchema("weekly-schedule-normalization", parseSchema())
                )
        );
    }

    private JsonNode parseSchema() {
        try {
            return objectMapper.readTree(RESPONSE_SCHEMA);
        } catch (Exception exception) {
            log.error("Failed to prepare schedule normalization schema.", exception);
            throw new IllegalStateException("AI 시간표 정규화 요청을 준비하지 못했습니다.");
        }
    }

    private ScheduleImportPayload canonicalizePayload(String rawContent) {
        try {
            JsonNode payload = objectMapper.readTree(rawContent);
            if (!payload.isObject()) {
                throw new IllegalStateException("vLLM output must be a JSON object.");
            }

            List<ImportedScheduleBlock> blocks = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            JsonNode rawWarnings = payload.path("warnings");
            if (rawWarnings.isArray()) {
                rawWarnings.forEach(warning -> {
                    String normalized = stableText(warning);
                    if (normalized != null) {
                        warnings.add(normalized);
                    }
                });
            }

            JsonNode rawBlocks = payload.path("blocks");
            if (rawBlocks.isArray()) {
                rawBlocks.forEach(rawBlock -> {
                    if (!rawBlock.isObject()) {
                        return;
                    }

                    String day = normalizeDay(rawBlock.get("dayOfWeek"));
                    String startTime = normalizeTime(rawBlock.get("startTime"));
                    String endTime = normalizeTime(rawBlock.get("endTime"));
                    String activity = stableText(rawBlock.get("activity"));
                    String note = stableText(rawBlock.get("note"));

                    if (day == null || startTime == null || endTime == null || activity == null) {
                        String snippet = activity != null ? activity : rawBlock.toString();
                        warnings.add("Skipped ambiguous block: " + snippet);
                        return;
                    }

                    blocks.add(new ImportedScheduleBlock(
                            DayOfWeek.valueOf(day),
                            LocalTime.parse(startTime),
                            LocalTime.parse(endTime),
                            activity,
                            normalizeCategory(rawBlock.get("category")),
                            note
                    ));
                });
            }

            return new ScheduleImportPayload(blocks, warnings);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Failed to parse vLLM schedule normalization response.", exception);
            throw new IllegalStateException("AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String normalizeDay(JsonNode value) {
        String text = uppercaseText(value);
        if (text == null) {
            return null;
        }
        String normalized = DAY_ALIASES.getOrDefault(text, text);
        return DAY_VALUES.contains(normalized) ? normalized : null;
    }

    private String normalizeTime(JsonNode value) {
        String text = stableText(value);
        if (text == null) {
            return null;
        }

        if (text.matches("^([01]\\d|2[0-3]):([0-5]\\d)$")) {
            return text;
        }
        if (!text.matches("^\\d{1,2}:\\d{2}$")) {
            return null;
        }

        String[] parts = text.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }
        return "%02d:%02d".formatted(hour, minute);
    }

    private String normalizeCategory(JsonNode value) {
        String text = uppercaseText(value);
        if (text == null) {
            return "LIFE";
        }
        String normalized = CATEGORY_ALIASES.getOrDefault(text, "LIFE");
        return CATEGORY_VALUES.contains(normalized) ? normalized : "LIFE";
    }

    private String uppercaseText(JsonNode value) {
        String text = stableText(value);
        return text == null ? null : text.toUpperCase();
    }

    private String stableText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    public record ScheduleImportPayload(
            List<ImportedScheduleBlock> blocks,
            List<String> warnings
    ) {
    }

    public record ImportedScheduleBlock(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            String activity,
            String category,
            String note
    ) {
    }

    private record ChatCompletionRequest(
            String model,
            List<RequestMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
    }

    private record RequestMessage(
            String role,
            String content
    ) {
    }

    private record ResponseFormat(
            String type,
            @JsonProperty("json_schema") JsonSchema jsonSchema
    ) {
    }

    private record JsonSchema(
            String name,
            JsonNode schema
    ) {
    }

    private record ChatCompletionResponse(
            List<Choice> choices
    ) {
    }

    private record Choice(
            AssistantMessage message
    ) {
    }

    private record AssistantMessage(
            String content
    ) {
    }
}
