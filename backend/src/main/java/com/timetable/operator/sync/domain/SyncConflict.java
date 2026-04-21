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
@Table(name = "sync_conflicts")
public class SyncConflict extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID syncLogId;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncTargetSystem targetSystem;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String details;

    private String localRef;

    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncConflictStatus status;

    @Enumerated(EnumType.STRING)
    private SyncConflictResolution resolution;

    @Column(columnDefinition = "text")
    private String payload;

    private Instant resolvedAt;
}
