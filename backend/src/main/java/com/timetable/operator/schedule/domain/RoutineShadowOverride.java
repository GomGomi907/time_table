package com.timetable.operator.schedule.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "routine_shadow_overrides")
public class RoutineShadowOverride extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID scheduleBlockId;

    @Column(nullable = false)
    private LocalDate shadowDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoutineShadowState shadowState = RoutineShadowState.NONE;

    @Enumerated(EnumType.STRING)
    private ShadowingEntityType shadowingEntityType;

    private UUID shadowingEntityId;

    @Column(nullable = false, length = 500)
    private String reason;

    private Instant resolvedAt;
}
