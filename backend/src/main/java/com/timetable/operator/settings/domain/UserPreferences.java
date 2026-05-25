package com.timetable.operator.settings.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_preferences")
public class UserPreferences extends AuditableEntity {

    @Column(nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false)
    private LocalTime quietHoursStart;

    @Column(nullable = false)
    private LocalTime quietHoursEnd;

    @Column(nullable = false)
    private Integer bufferMinutes;

    @Column(nullable = false)
    private Integer overtimeTriggerMinutes;

    @Column(nullable = false)
    private Integer openGapTriggerMinutes;

    @Column(nullable = false)
    private Integer preferredFocusMinutes = 45;

    @Column(nullable = false)
    private Integer breakBufferMinutes = 10;

    @Column(nullable = false)
    private String interventionFrequency;
}
