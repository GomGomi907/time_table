package com.timetable.operator.schedule.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "schedule_blocks")
public class ScheduleBlock extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private String activity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleCategory category;

    @Column(length = 1000)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleSourceType sourceType;

    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoutineShadowPolicy shadowPolicy = RoutineShadowPolicy.AUTO_SHADOW;

    @Column(nullable = false)
    private boolean protectedWindow;

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
