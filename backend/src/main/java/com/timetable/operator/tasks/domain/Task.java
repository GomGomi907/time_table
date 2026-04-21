package com.timetable.operator.tasks.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import com.timetable.operator.common.domain.PlannerSyncState;
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
@Table(name = "tasks")
public class Task extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID goalId;

    private UUID eventId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    private String category;

    private Instant dueDate;

    @Column(nullable = false)
    private int estimatedMinutes;

    @Column(nullable = false)
    private int actualMinutes;

    private Instant completedAt;

    @Column(nullable = false)
    private short priority = 3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskSourceType sourceType = TaskSourceType.LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlannerSyncState syncState = PlannerSyncState.LOCAL_ONLY;

    private UUID forkedFromTaskId;

    private String externalSourceId;

    private String externalEtag;

    private Instant lastSyncedAt;
}
