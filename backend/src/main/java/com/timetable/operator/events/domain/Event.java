package com.timetable.operator.events.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.schedule.domain.ScheduleCategory;
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
@Table(name = "events")
public class Event extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID goalId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleCategory category = ScheduleCategory.GROWTH;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    private Instant actualStartAt;

    private Instant actualEndAt;

    @Column(nullable = false)
    private short priority = 3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventSourceType sourceType = EventSourceType.LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlannerSyncState syncState = PlannerSyncState.LOCAL_ONLY;

    private UUID forkedFromEventId;

    private String externalSourceId;

    private String externalEtag;

    private Instant lastSyncedAt;
}
