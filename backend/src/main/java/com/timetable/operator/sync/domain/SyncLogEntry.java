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
@Table(name = "sync_logs")
public class SyncLogEntry extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String syncType;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncTargetSystem targetSystem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncTriggerSource triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncResolvePolicy resolvePolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncExecutionStatus status;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(nullable = false)
    private Integer affectedCount = 0;

    private Instant rangeStart;

    private Instant rangeEnd;

    private String webhookChannelId;

    private String webhookResourceId;

    private String webhookResourceState;

    private Long webhookMessageNumber;

    @Column(columnDefinition = "text")
    private String webhookResourceUri;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;
}
