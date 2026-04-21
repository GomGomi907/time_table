package com.timetable.operator.engine.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "focus_sessions")
public class FocusSession extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID scheduledBlockId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FocusSessionStatus status;

    private OffsetDateTime startedAt;
    private OffsetDateTime pausedAt;
    private Integer remainingMinutes;

    @Column(nullable = false)
    private boolean isPaused = false;
}
