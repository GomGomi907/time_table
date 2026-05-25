package com.timetable.operator.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.tasks.domain.Task;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(value = "app.sync.google.mock-enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
class GoogleRestOutboundSyncClient implements GoogleOutboundSyncClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GOOGLE_API_BASE_URL = "https://www.googleapis.com";
    private static final String GOOGLE_TASKS_BASE_URL = "https://tasks.googleapis.com";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GoogleAccessTokenService googleAccessTokenService;

    @Override
    public ProviderWriteResult createCalendarEvent(CalendarConnection connection, Event event) {
        JsonNode response = webClientBuilder.baseUrl(GOOGLE_API_BASE_URL).build()
                .post()
                .uri("/calendar/v3/calendars/primary/events")
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .bodyValue(calendarPayload(event))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
        return result(response);
    }

    @Override
    public ProviderWriteResult updateCalendarEvent(CalendarConnection connection, String providerEventId, Event event) {
        JsonNode response = webClientBuilder.baseUrl(GOOGLE_API_BASE_URL).build()
                .put()
                .uri("/calendar/v3/calendars/primary/events/{eventId}", providerEventId)
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .bodyValue(calendarPayload(event))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
        return result(response);
    }

    @Override
    public void deleteCalendarEvent(CalendarConnection connection, String providerEventId) {
        webClientBuilder.baseUrl(GOOGLE_API_BASE_URL).build()
                .delete()
                .uri("/calendar/v3/calendars/primary/events/{eventId}", providerEventId)
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .retrieve()
                .bodyToMono(Void.class)
                .block(REQUEST_TIMEOUT);
    }

    @Override
    public ProviderWriteResult createTask(CalendarConnection connection, String taskListId, Task task) {
        JsonNode response = webClientBuilder.baseUrl(GOOGLE_TASKS_BASE_URL).build()
                .post()
                .uri("/tasks/v1/lists/{tasklist}/tasks", taskListId)
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .bodyValue(taskPayload(task))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
        return result(response);
    }

    @Override
    public ProviderWriteResult updateTask(CalendarConnection connection, String taskListId, String providerTaskId, Task task) {
        JsonNode response = webClientBuilder.baseUrl(GOOGLE_TASKS_BASE_URL).build()
                .put()
                .uri("/tasks/v1/lists/{tasklist}/tasks/{task}", taskListId, providerTaskId)
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .bodyValue(taskPayload(task))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(REQUEST_TIMEOUT);
        return result(response);
    }

    @Override
    public void deleteTask(CalendarConnection connection, String taskListId, String providerTaskId) {
        webClientBuilder.baseUrl(GOOGLE_TASKS_BASE_URL).build()
                .delete()
                .uri("/tasks/v1/lists/{tasklist}/tasks/{task}", taskListId, providerTaskId)
                .headers(headers -> headers.setBearerAuth(validAccessToken(connection)))
                .retrieve()
                .bodyToMono(Void.class)
                .block(REQUEST_TIMEOUT);
    }

    private Map<String, Object> calendarPayload(Event event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", event.getTitle());
        payload.put("description", event.getDescription());
        payload.put("start", Map.of("dateTime", event.getStartAt().toString(), "timeZone", "UTC"));
        payload.put("end", Map.of("dateTime", event.getEndAt().toString(), "timeZone", "UTC"));
        return payload;
    }

    private Map<String, Object> taskPayload(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", task.getTitle());
        payload.put("notes", task.getDescription());
        if (task.getDueDate() != null) {
            payload.put("due", task.getDueDate().toString());
        }
        if (task.getStatus() != null) {
            payload.put("status", task.getStatus().name().equals("DONE") ? "completed" : "needsAction");
        }
        return payload;
    }

    private ProviderWriteResult result(JsonNode response) {
        if (response == null) {
            return new ProviderWriteResult(null, null, null);
        }
        return new ProviderWriteResult(text(response, "id"), text(response, "etag"), response.toString());
    }

    private String validAccessToken(CalendarConnection connection) {
        return googleAccessTokenService.validAccessToken(connection);
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
