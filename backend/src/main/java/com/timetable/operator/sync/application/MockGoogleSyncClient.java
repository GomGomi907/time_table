package com.timetable.operator.sync.application;

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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "app.sync.google.mock-enabled", havingValue = "true")
@RequiredArgsConstructor
class MockGoogleSyncClient implements GoogleInboundSyncClient, GoogleOutboundSyncClient {

    private static final String CALENDAR_EXTERNAL_PREFIX = "google_calendar:";
    private static final String TASK_EXTERNAL_PREFIX = "google_tasks:";
    private static final String MOCK_CALENDAR_ID = "mock-calendar-inbound-event";
    private static final String MOCK_TASK_LIST_ID = "@default";
    private static final String MOCK_TASK_ID = "mock-tasks-inbound-task";

    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final SyncMappingRepository syncMappingRepository;

    @Override
    public InboundSyncResult importCalendar(CalendarConnection connection, Instant rangeStart, Instant rangeEnd) {
        Instant startAt = Instant.now().plus(Duration.ofHours(2));
        Instant endAt = startAt.plus(Duration.ofMinutes(45));

        Event event = findMappedEvent(connection, MOCK_CALENDAR_ID)
                .or(() -> eventRepository.findByUserIdAndExternalSourceId(
                        connection.getUserId(),
                        CALENDAR_EXTERNAL_PREFIX + MOCK_CALENDAR_ID
                ))
                .orElseGet(Event::new);
        event.setUserId(connection.getUserId());
        event.setTitle("제품 리뷰 회의");
        event.setDescription("로컬 E2E용 Google Calendar mock inbound event");
        event.setCategory(ScheduleCategory.WORK);
        event.setStartAt(startAt);
        event.setEndAt(endAt);
        event.setStatus(EventStatus.PLANNED);
        event.setSourceType(EventSourceType.GOOGLE_CALENDAR);
        event.setSyncState(PlannerSyncState.IMPORTED);
        event.setExternalSourceId(CALENDAR_EXTERNAL_PREFIX + MOCK_CALENDAR_ID);
        event.setExternalEtag("mock-calendar-etag-1");
        event.setLastSyncedAt(Instant.now());
        Event saved = eventRepository.save(event);

        upsertMapping(
                connection,
                SyncMappingLocalType.EVENT,
                saved.getId(),
                SyncProvider.GOOGLE_CALENDAR,
                MOCK_CALENDAR_ID,
                "mock-calendar-etag-1",
                "{\"calendarId\":\"primary\",\"mock\":true}"
        );
        return new InboundSyncResult(1, "Mock Google Calendar inbound read applied 1 local event change.");
    }

    @Override
    public InboundSyncResult importTasks(CalendarConnection connection, Instant rangeStart, Instant rangeEnd) {
        String providerExternalId = MOCK_TASK_LIST_ID + ":" + MOCK_TASK_ID;
        Task task = findMappedTask(connection, providerExternalId)
                .or(() -> taskRepository.findByUserIdAndExternalSourceId(
                        connection.getUserId(),
                        TASK_EXTERNAL_PREFIX + providerExternalId
                ))
                .orElseGet(Task::new);
        task.setUserId(connection.getUserId());
        task.setTitle("오늘 할 일 정리");
        task.setDescription("로컬 E2E용 Google Tasks mock inbound task");
        task.setCategory("Google Tasks");
        task.setDueDate(Instant.now().plus(Duration.ofHours(4)));
        task.setEstimatedMinutes(task.getEstimatedMinutes() <= 0 ? 30 : task.getEstimatedMinutes());
        task.setActualMinutes(Math.max(task.getActualMinutes(), 0));
        task.setStatus(TaskStatus.TODO);
        task.setSourceType(TaskSourceType.GOOGLE_TASKS);
        task.setSyncState(PlannerSyncState.IMPORTED);
        task.setExternalSourceId(TASK_EXTERNAL_PREFIX + providerExternalId);
        task.setExternalEtag("mock-task-etag-1");
        task.setLastSyncedAt(Instant.now());
        Task saved = taskRepository.save(task);

        upsertMapping(
                connection,
                SyncMappingLocalType.TASK,
                saved.getId(),
                SyncProvider.GOOGLE_TASKS,
                providerExternalId,
                "mock-task-etag-1",
                "{\"taskListId\":\"@default\",\"taskListTitle\":\"Google Tasks\",\"mock\":true}"
        );
        return new InboundSyncResult(1, "Mock Google Tasks inbound read applied 1 local task change.");
    }

    @Override
    public ProviderWriteResult createCalendarEvent(CalendarConnection connection, Event event) {
        return result("mock-calendar-" + shortId(event.getId()));
    }

    @Override
    public ProviderWriteResult updateCalendarEvent(CalendarConnection connection, String providerEventId, Event event) {
        return result(providerEventId);
    }

    @Override
    public void deleteCalendarEvent(CalendarConnection connection, String providerEventId) {
        // Local mock records the delete through outbox/mapping state in ProviderWriteProcessor.
    }

    @Override
    public ProviderWriteResult createTask(CalendarConnection connection, String taskListId, Task task) {
        return result("mock-task-" + shortId(task.getId()));
    }

    @Override
    public ProviderWriteResult updateTask(CalendarConnection connection, String taskListId, String providerTaskId, Task task) {
        return result(providerTaskId);
    }

    @Override
    public void deleteTask(CalendarConnection connection, String taskListId, String providerTaskId) {
        // Local mock records the delete through outbox/mapping state in ProviderWriteProcessor.
    }

    private ProviderWriteResult result(String providerId) {
        return new ProviderWriteResult(providerId, "mock-etag-" + providerId, "{\"mock\":true,\"id\":\"%s\"}".formatted(providerId));
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
        mapping.setSyncStatus(SyncMappingStatus.ACTIVE);
        mapping.setTombstoneState(TombstoneState.NONE);
        mapping.setLastSyncedAt(Instant.now());
        mapping.setMetadata(metadata);
        syncMappingRepository.save(mapping);
    }

    private String shortId(java.util.UUID id) {
        return id == null ? "new" : id.toString().substring(0, 8);
    }
}
