package com.timetable.operator.sync.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "provider_write_outbox")
public class ProviderWriteOutbox extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncMappingLocalType localType;

    @Column(nullable = false)
    private UUID localId;

    private UUID mappingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderWriteOperation operation;

    @Column(columnDefinition = "text")
    private String payloadSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProviderWriteState state;

    @Column(nullable = false)
    private int attemptCount;

    private String lastErrorCode;

    @Column(columnDefinition = "text")
    private String lastErrorMessage;

    private Instant nextRetryAt;

    private Instant inFlightAt;

    private Instant appliedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
