package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.SyncMapping;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncMappingRepository extends JpaRepository<SyncMapping, UUID> {

    List<SyncMapping> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<SyncMapping> findByUserIdAndProviderAndExternalId(
            UUID userId,
            SyncProvider provider,
            String externalId
    );

    Optional<SyncMapping> findByUserIdAndLocalTypeAndLocalIdAndProvider(
            UUID userId,
            SyncMappingLocalType localType,
            UUID localId,
            SyncProvider provider
    );
}
