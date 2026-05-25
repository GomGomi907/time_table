package com.timetable.operator.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.sync.domain.SyncMapping;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncMappingStatus;
import com.timetable.operator.sync.domain.SyncProvider;
import com.timetable.operator.sync.domain.TombstoneState;
import com.timetable.operator.sync.infrastructure.SyncMappingRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(value = "app.sync.google.mock-enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
class GoogleRestInboundSyncClient implements GoogleInboundSyncClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CALENDAR_EXTERNAL_PREFIX = "google_calendar:";
    private static final String TASK_EXTERNAL_PREFIX = "google_tasks:";

    private final WebClient.Builder webClientBuilder;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final GoogleAccessTokenService googleAccessTokenService;

    @Override
    public InboundSyncResult importCalendar(CalendarConnection connection, Instant rangeStart, Instant rangeEnd) {
        String accessToken = validAccessToken(connection);
        WebClient client = webClientBuilder.baseUrl("https://www.googleapis.com").build();

        int affectedCount = 0;
        String pageToken = null;
        do {
            String currentPageToken = pageToken;
            JsonNode payload = client.get()
                    .uri(builder -> {
                        builder.path("/calendar/v3/calendars/primary/events")
                                .queryParam("timeMin", rangeStart.toString())
                                .queryParam("timeMax", rangeEnd.toString())
                                .queryParam("singleEvents", true)
                                .queryParam("orderBy", "startTime")
                                .queryParam("showDeleted", true)
                                .queryParam("maxResults", 2500);
                        if (currentPageToken != null) {
                            builder.queryParam("pageToken", currentPageToken);
                        }
                        return builder.build();
                    })
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(REQUEST_TIMEOUT);

            affectedCount += upsertCalendarItems(connection, payload == null ? null : payload.path("items"));
            pageToken = text(payload, "nextPageToken");
        } while (pageToken != null);

        return new InboundSyncResult(affectedCount, "Google Calendar inbound read applied %d local event changes.".formatted(affectedCount));
    }

    @Override
    public InboundSyncResult importTasks(CalendarConnection connection, Instant rangeStart, Instant rangeEnd) {
        String accessToken = validAccessToken(connection);
        WebClient client = webClientBuilder.baseUrl("https://tasks.googleapis.com").build();

        int affectedCount = 0;
        for (TaskListItem taskList : fetchTaskLists(client, accessToken)) {
            String pageToken = null;
            do {
                String currentPageToken = pageToken;
                JsonNode payload = client.get()
                        .uri(builder -> {
                            builder.pathSegment("tasks", "v1", "lists", taskList.id(), "tasks")
                                    .queryParam("dueMin", rangeStart.toString())
                                    .queryParam("dueMax", rangeEnd.toString())
                                    .queryParam("showCompleted", true)
                                    .queryParam("showDeleted", true)
                                    .queryParam("showHidden", true)
                                    .queryParam("showAssigned", true)
                                    .queryParam("maxResults", 100);
                            if (currentPageToken != null) {
                                builder.queryParam("pageToken", currentPageToken);
                            }
                            return builder.build();
                        })
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(REQUEST_TIMEOUT);

                affectedCount += upsertTaskItems(connection, taskList, payload == null ? null : payload.path("items"));
                pageToken = text(payload, "nextPageToken");
            } while (pageToken != null);
        }

        return new InboundSyncResult(affectedCount, "Google Tasks inbound read applied %d local task changes.".formatted(affectedCount));
    }

    private List<TaskListItem> fetchTaskLists(WebClient client, String accessToken) {
        List<TaskListItem> taskLists = new ArrayList<>();
        String pageToken = null;
        do {
            String currentPageToken = pageToken;
            JsonNode payload = client.get()
                    .uri(builder -> {
                        builder.path("/tasks/v1/users/@me/lists")
                                .queryParam("maxResults", 1000);
                        if (currentPageToken != null) {
                            builder.queryParam("pageToken", currentPageToken);
                        }
                        return builder.build();
                    })
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(REQUEST_TIMEOUT);

            JsonNode items = payload == null ? null : payload.path("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String id = text(item, "id");
                    if (id != null) {
                        taskLists.add(new TaskListItem(id, fallback(text(item, "title"), "Google Tasks")));
                    }
                }
            }
            pageToken = text(payload, "nextPageToken");
        } while (pageToken != null);
        return taskLists;
    }

    private int upsertCalendarItems(CalendarConnection connection, JsonNode items) {
        if (items == null || !items.isArray()) {
            return 0;
        }

        int affectedCount = 0;
        for (JsonNode item : items) {
            String providerId = text(item, "id");
            if (providerId == null) {
                continue;
            }

            String externalSourceId = CALENDAR_EXTERNAL_PREFIX + providerId;
            Event existing = findMappedEvent(connection, providerId)
                    .or(() -> eventRepository.findByUserIdAndExternalSourceId(connection.getUserId(), externalSourceId))
                    .orElse(null);
            String status = fallback(text(item, "status"), "confirmed");
            String providerEtag = text(item, "etag");

            if ("cancelled".equalsIgnoreCase(status)) {
                if (existing != null) {
                    if (existing.getSyncState() == PlannerSyncState.DETACHED
                            && hasSameEtag(existing.getExternalEtag(), providerEtag)) {
                        continue;
                    }
                    existing.setStatus(EventStatus.CANCELLED);
                    existing.setSyncState(PlannerSyncState.DETACHED);
                    existing.setExternalEtag(providerEtag);
                    existing.setLastSyncedAt(Instant.now());
                    eventRepository.save(existing);
                    upsertMapping(
                            connection,
                            SyncMappingLocalType.EVENT,
                            existing.getId(),
                            SyncProvider.GOOGLE_CALENDAR,
                            providerId,
                            providerEtag,
                            SyncMappingStatus.REMOTE_DELETED,
                            TombstoneState.REMOTE_DELETED,
                            "{\"calendarId\":\"primary\"}"
                    );
                    affectedCount++;
                }
                continue;
            }

            if (existing != null && existing.getSyncState() == PlannerSyncState.FORKED) {
                continue;
            }
            if (existing != null && existing.getSyncState() == PlannerSyncState.DIRTY_PENDING_WRITE) {
                continue;
            }
            if (existing != null && existing.getSyncState() == PlannerSyncState.IMPORTED
                    && hasSameEtag(existing.getExternalEtag(), providerEtag)) {
                continue;
            }

            Instant startAt = readGoogleDateTime(item.path("start"), Instant.now());
            Instant endAt = readGoogleDateTime(item.path("end"), startAt.plus(Duration.ofMinutes(30)));
            if (!endAt.isAfter(startAt)) {
                endAt = startAt.plus(Duration.ofMinutes(30));
            }

            Event event = existing == null ? new Event() : existing;
            event.setUserId(connection.getUserId());
            event.setTitle(fallback(text(item, "summary"), "Google Calendar event"));
            event.setDescription(text(item, "description"));
            event.setCategory(ScheduleCategory.WORK);
            event.setStartAt(startAt);
            event.setEndAt(endAt);
            event.setStatus(EventStatus.PLANNED);
            event.setSourceType(EventSourceType.GOOGLE_CALENDAR);
            event.setSyncState(PlannerSyncState.IMPORTED);
            event.setExternalSourceId(externalSourceId);
            event.setExternalEtag(providerEtag);
            event.setLastSyncedAt(Instant.now());
            Event saved = eventRepository.save(event);
            upsertMapping(
                    connection,
                    SyncMappingLocalType.EVENT,
                    saved.getId(),
                    SyncProvider.GOOGLE_CALENDAR,
                    providerId,
                    providerEtag,
                    SyncMappingStatus.ACTIVE,
                    TombstoneState.NONE,
                    "{\"calendarId\":\"primary\"}"
            );
            affectedCount++;
        }
        return affectedCount;
    }

    private int upsertTaskItems(CalendarConnection connection, TaskListItem taskList, JsonNode items) {
        if (items == null || !items.isArray()) {
            return 0;
        }

        int affectedCount = 0;
        for (JsonNode item : items) {
            String providerId = text(item, "id");
            if (providerId == null) {
                continue;
            }

            String externalSourceId = TASK_EXTERNAL_PREFIX + taskList.id() + ":" + providerId;
            String providerExternalId = taskList.id() + ":" + providerId;
            Task existing = findMappedTask(connection, providerExternalId)
                    .or(() -> taskRepository.findByUserIdAndExternalSourceId(connection.getUserId(), externalSourceId))
                    .orElse(null);
            String providerEtag = text(item, "etag");
            if (item.path("deleted").asBoolean(false)) {
                if (existing != null) {
                    if (existing.getSyncState() == PlannerSyncState.DETACHED
                            && hasSameEtag(existing.getExternalEtag(), providerEtag)) {
                        continue;
                    }
                    existing.setStatus(TaskStatus.CANCELLED);
                    existing.setSyncState(PlannerSyncState.DETACHED);
                    existing.setExternalEtag(providerEtag);
                    existing.setLastSyncedAt(Instant.now());
                    taskRepository.save(existing);
                    upsertMapping(
                            connection,
                            SyncMappingLocalType.TASK,
                            existing.getId(),
                            SyncProvider.GOOGLE_TASKS,
                            providerExternalId,
                            providerEtag,
                            SyncMappingStatus.REMOTE_DELETED,
                            TombstoneState.REMOTE_DELETED,
                            "{\"taskListId\":\"%s\",\"taskListTitle\":\"%s\"}".formatted(taskList.id(), escapeJson(taskList.title()))
                    );
                    affectedCount++;
                }
                continue;
            }

            if (existing != null && existing.getSyncState() == PlannerSyncState.FORKED) {
                continue;
            }
            if (existing != null && existing.getSyncState() == PlannerSyncState.DIRTY_PENDING_WRITE) {
                continue;
            }
            if (existing != null && existing.getSyncState() == PlannerSyncState.IMPORTED
                    && hasSameEtag(existing.getExternalEtag(), providerEtag)) {
                continue;
            }

            Task task = existing == null ? new Task() : existing;
            task.setUserId(connection.getUserId());
            task.setTitle(fallback(text(item, "title"), "Google Task"));
            task.setDescription(text(item, "notes"));
            task.setCategory(taskList.title());
            task.setDueDate(parseInstantOrNull(text(item, "due")));
            task.setEstimatedMinutes(task.getEstimatedMinutes() <= 0 ? 30 : task.getEstimatedMinutes());
            task.setActualMinutes(Math.max(task.getActualMinutes(), 0));
            task.setStatus("completed".equalsIgnoreCase(text(item, "status")) ? TaskStatus.DONE : TaskStatus.TODO);
            task.setCompletedAt(parseInstantOrNull(text(item, "completed")));
            task.setSourceType(TaskSourceType.GOOGLE_TASKS);
            task.setSyncState(PlannerSyncState.IMPORTED);
            task.setExternalSourceId(externalSourceId);
            task.setExternalEtag(providerEtag);
            task.setLastSyncedAt(Instant.now());
            Task saved = taskRepository.save(task);
            upsertMapping(
                    connection,
                    SyncMappingLocalType.TASK,
                    saved.getId(),
                    SyncProvider.GOOGLE_TASKS,
                    providerExternalId,
                    providerEtag,
                    SyncMappingStatus.ACTIVE,
                    TombstoneState.NONE,
                    "{\"taskListId\":\"%s\",\"taskListTitle\":\"%s\"}".formatted(taskList.id(), escapeJson(taskList.title()))
            );
            affectedCount++;
        }
        return affectedCount;
    }

    private Optional<Event> findMappedEvent(CalendarConnection connection, String providerId) {
        return syncMappingRepository.findByProviderAndExternalId(SyncProvider.GOOGLE_CALENDAR, providerId)
                .filter(mapping -> mapping.getUserId().equals(connection.getUserId()))
                .filter(mapping -> mapping.getLocalType() == SyncMappingLocalType.EVENT)
                .flatMap(mapping -> eventRepository.findById(mapping.getLocalId()));
    }

    private Optional<Task> findMappedTask(CalendarConnection connection, String providerExternalId) {
        return syncMappingRepository.findByProviderAndExternalId(SyncProvider.GOOGLE_TASKS, providerExternalId)
                .filter(mapping -> mapping.getUserId().equals(connection.getUserId()))
                .filter(mapping -> mapping.getLocalType() == SyncMappingLocalType.TASK)
                .flatMap(mapping -> taskRepository.findById(mapping.getLocalId()));
    }

    private void upsertMapping(
            CalendarConnection connection,
            SyncMappingLocalType localType,
            java.util.UUID localId,
            SyncProvider provider,
            String externalId,
            String externalEtag,
            SyncMappingStatus status,
            TombstoneState tombstoneState,
            String metadata
    ) {
        SyncMapping mapping = syncMappingRepository.findByProviderAndExternalId(provider, externalId)
                .orElseGet(SyncMapping::new);
        mapping.setUserId(connection.getUserId());
        mapping.setLocalType(localType);
        mapping.setLocalId(localId);
        mapping.setProvider(provider);
        mapping.setExternalId(externalId);
        mapping.setExternalEtag(externalEtag);
        mapping.setSyncStatus(status);
        mapping.setTombstoneState(tombstoneState);
        mapping.setRemoteDeletedAt(tombstoneState == TombstoneState.REMOTE_DELETED ? Instant.now() : null);
        mapping.setLastSyncedAt(Instant.now());
        mapping.setMetadata(metadata);
        syncMappingRepository.save(mapping);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String validAccessToken(CalendarConnection connection) {
        return googleAccessTokenService.validAccessToken(connection);
    }

    private Instant readGoogleDateTime(JsonNode node, Instant fallback) {
        String dateTime = text(node, "dateTime");
        if (dateTime != null) {
            return OffsetDateTime.parse(dateTime).toInstant();
        }

        String date = text(node, "date");
        if (date != null) {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return fallback;
    }

    private Instant parseInstantOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        String value = field.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasSameEtag(String currentEtag, String providerEtag) {
        return providerEtag != null && providerEtag.equals(currentEtag);
    }

    private record TaskListItem(
            String id,
            String title
    ) {
    }
}
