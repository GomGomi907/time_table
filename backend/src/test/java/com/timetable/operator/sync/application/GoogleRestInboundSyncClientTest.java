package com.timetable.operator.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.sync.domain.SyncProvider;
import com.timetable.operator.sync.infrastructure.SyncMappingRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GoogleRestInboundSyncClientTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final SyncMappingRepository syncMappingRepository = mock(SyncMappingRepository.class);
    private final GoogleAccessTokenService googleAccessTokenService = mock(GoogleAccessTokenService.class);

    @Test
    void importCalendarSkipsUnchangedImportedEventByEtag() {
        UUID userId = UUID.randomUUID();
        Event existing = new Event();
        existing.setUserId(userId);
        existing.setExternalSourceId("google_calendar:event-1");
        existing.setExternalEtag("\"etag-1\"");
        existing.setSyncState(PlannerSyncState.IMPORTED);

        when(eventRepository.findByUserIdAndExternalSourceId(userId, "google_calendar:event-1"))
                .thenReturn(Optional.of(existing));
        when(syncMappingRepository.findByProviderAndExternalId(SyncProvider.GOOGLE_CALENDAR, "event-1"))
                .thenReturn(Optional.empty());
        when(googleAccessTokenService.validAccessToken(any(CalendarConnection.class))).thenReturn("access-token");

        GoogleRestInboundSyncClient client = new GoogleRestInboundSyncClient(
                jsonClient("""
                        {
                          "items": [
                            {
                              "id": "event-1",
                              "etag": "\\"etag-1\\"",
                              "status": "confirmed",
                              "summary": "Planning",
                              "start": { "dateTime": "2026-05-02T01:00:00Z" },
                              "end": { "dateTime": "2026-05-02T02:00:00Z" }
                            }
                          ]
                        }
                """),
                eventRepository,
                taskRepository,
                syncMappingRepository,
                googleAccessTokenService
        );

        GoogleInboundSyncClient.InboundSyncResult result = client.importCalendar(
                connection(userId),
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-08T00:00:00Z")
        );

        assertThat(result.affectedCount()).isZero();
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void importTasksSkipsUnchangedImportedTaskByEtag() {
        UUID userId = UUID.randomUUID();
        Task existing = new Task();
        existing.setUserId(userId);
        existing.setExternalSourceId("google_tasks:list-1:task-1");
        existing.setExternalEtag("\"task-etag-1\"");
        existing.setSyncState(PlannerSyncState.IMPORTED);

        when(taskRepository.findByUserIdAndExternalSourceId(userId, "google_tasks:list-1:task-1"))
                .thenReturn(Optional.of(existing));
        when(syncMappingRepository.findByProviderAndExternalId(SyncProvider.GOOGLE_TASKS, "list-1:task-1"))
                .thenReturn(Optional.empty());
        when(googleAccessTokenService.validAccessToken(any(CalendarConnection.class))).thenReturn("access-token");

        GoogleRestInboundSyncClient client = new GoogleRestInboundSyncClient(
                WebClient.builder().exchangeFunction(request -> {
                    String path = request.url().getPath();
                    String body = path.equals("/tasks/v1/users/@me/lists")
                            ? """
                              {
                                "items": [
                                  { "id": "list-1", "title": "Inbox" }
                                ]
                              }
                              """
                            : """
                              {
                                "items": [
                                  {
                                    "id": "task-1",
                                    "etag": "\\"task-etag-1\\"",
                                    "title": "Write brief",
                                    "status": "needsAction",
                                    "due": "2026-05-03T00:00:00Z"
                                  }
                                ]
                              }
                              """;
                    return Mono.just(jsonResponse(body));
                }),
                eventRepository,
                taskRepository,
                syncMappingRepository,
                googleAccessTokenService
        );

        GoogleInboundSyncClient.InboundSyncResult result = client.importTasks(
                connection(userId),
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-08T00:00:00Z")
        );

        assertThat(result.affectedCount()).isZero();
        verify(taskRepository, never()).save(any(Task.class));
    }

    private CalendarConnection connection(UUID userId) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(userId);
        connection.setAccessToken("access-token");
        connection.setTokenExpiresAt(Instant.parse("2026-06-01T00:00:00Z"));
        return connection;
    }

    private WebClient.Builder jsonClient(String body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(jsonResponse(body)));
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
