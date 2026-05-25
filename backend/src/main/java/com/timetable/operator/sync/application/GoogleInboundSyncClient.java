package com.timetable.operator.sync.application;

import com.timetable.operator.calendar.domain.CalendarConnection;
import java.time.Instant;

public interface GoogleInboundSyncClient {

    InboundSyncResult importCalendar(CalendarConnection connection, Instant rangeStart, Instant rangeEnd);

    InboundSyncResult importTasks(CalendarConnection connection, Instant rangeStart, Instant rangeEnd);

    record InboundSyncResult(
            int affectedCount,
            String detail
    ) {
    }
}
