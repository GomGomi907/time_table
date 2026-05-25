package com.timetable.operator.sync.application;

import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.tasks.domain.Task;

public interface GoogleOutboundSyncClient {

    ProviderWriteResult createCalendarEvent(CalendarConnection connection, Event event);

    ProviderWriteResult updateCalendarEvent(CalendarConnection connection, String providerEventId, Event event);

    void deleteCalendarEvent(CalendarConnection connection, String providerEventId);

    ProviderWriteResult createTask(CalendarConnection connection, String taskListId, Task task);

    ProviderWriteResult updateTask(CalendarConnection connection, String taskListId, String providerTaskId, Task task);

    void deleteTask(CalendarConnection connection, String taskListId, String providerTaskId);

    record ProviderWriteResult(
            String providerId,
            String externalEtag,
            String rawPayload
    ) {
    }
}
