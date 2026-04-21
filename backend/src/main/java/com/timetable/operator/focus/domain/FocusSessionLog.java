package com.timetable.operator.focus.domain;

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
@Table(name = "focus_session_logs")
public class FocusSessionLog extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID eventId;

    private UUID taskId;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    private FocusCompletionType completionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FocusTriggerSource triggerSource = FocusTriggerSource.MANUAL;

    @Column(columnDefinition = "text")
    private String memo;
}
