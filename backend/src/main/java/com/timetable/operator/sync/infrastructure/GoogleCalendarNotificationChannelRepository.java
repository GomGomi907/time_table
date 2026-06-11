package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.GoogleCalendarNotificationChannel;
import com.timetable.operator.sync.domain.GoogleNotificationChannelStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoogleCalendarNotificationChannelRepository
        extends JpaRepository<GoogleCalendarNotificationChannel, UUID> {

    Optional<GoogleCalendarNotificationChannel> findByChannelIdAndResourceIdAndStatusIn(
            String channelId,
            String resourceId,
            Collection<GoogleNotificationChannelStatus> statuses
    );

    List<GoogleCalendarNotificationChannel> findByUserIdAndCalendarIdAndStatusAndExpirationAtBeforeOrderByExpirationAtAsc(
            UUID userId,
            String calendarId,
            GoogleNotificationChannelStatus status,
            Instant expirationAt
    );
}
