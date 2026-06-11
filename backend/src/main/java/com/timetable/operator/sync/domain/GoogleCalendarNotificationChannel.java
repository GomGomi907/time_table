package com.timetable.operator.sync.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "google_calendar_notification_channels")
public class GoogleCalendarNotificationChannel extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID calendarConnectionId;

    @Column(nullable = false)
    private String calendarId = "primary";

    @Column(nullable = false)
    private String channelId;

    @Column(nullable = false)
    private String resourceId;

    @Column(columnDefinition = "text")
    private String resourceUri;

    @Column(nullable = false, columnDefinition = "text")
    private String channelTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoogleNotificationChannelStatus status = GoogleNotificationChannelStatus.ACTIVE;

    private Instant expirationAt;

    private Long lastMessageNumber;

    private Instant lastNotificationAt;

    private String replacedByChannelId;
}
