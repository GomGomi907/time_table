package com.timetable.operator.sync.application;

import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.tasks.domain.Task;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "app.sync.google.mock-enabled", havingValue = "true")
class MockGoogleSyncClient implements GoogleInboundSyncClient, GoogleOutboundSyncClient {

    @Override
    public InboundSyncResult importCalendar(CalendarConnection connection, String calendarId, Instant rangeStart, Instant rangeEnd) {
        return new InboundSyncResult(0, "No local calendar fixture is created.");
    }

    @Override
    public InboundSyncResult importTasks(CalendarConnection connection, Instant rangeStart, Instant rangeEnd) {
        return new InboundSyncResult(0, "No local task fixture is created.");
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

    private String shortId(java.util.UUID id) {
        return id == null ? "new" : id.toString().substring(0, 8);
    }
}
