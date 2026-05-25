package com.timetable.operator.calendar.domain;

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
@Table(name = "calendar_connections")
public class CalendarConnection extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CalendarConnectionStatus status;

    private String googleSubject;

    private String email;

    @Column(columnDefinition = "text")
    private String accessToken;

    @Column(columnDefinition = "text")
    private String refreshToken;

    private Instant tokenExpiresAt;

    private Instant lastSuccessfulSyncAt;

    @Column(columnDefinition = "text")
    private String lastSyncError;

    @Column(columnDefinition = "text")
    private String grantedScopes;

    @Column(nullable = false)
    private boolean calendarReadEnabled;

    @Column(nullable = false)
    private boolean calendarWriteEnabled;

    @Column(nullable = false)
    private boolean tasksReadEnabled;

    @Column(nullable = false)
    private boolean tasksWriteEnabled;

    private Instant capabilityCheckedAt;

    private String capabilityStatus;

    @Column(columnDefinition = "text")
    private String capabilityError;
}
