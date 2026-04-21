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
@Table(name = "sync_mappings")
public class SyncMapping extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncMappingLocalType localType;

    @Column(nullable = false)
    private UUID localId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncProvider provider;

    @Column(nullable = false)
    private String externalId;

    private String externalEtag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncMappingStatus syncStatus = SyncMappingStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TombstoneState tombstoneState = TombstoneState.NONE;

    private Instant remoteDeletedAt;

    private Instant localDeletedAt;

    private Instant lastSyncedAt;

    @Column(columnDefinition = "text")
    private String metadata;
}
