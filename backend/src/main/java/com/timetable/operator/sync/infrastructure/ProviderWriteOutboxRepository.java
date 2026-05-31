package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.ProviderWriteOutbox;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncProvider;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderWriteOutboxRepository extends JpaRepository<ProviderWriteOutbox, UUID> {

    long countByUserIdAndStateIn(UUID userId, Collection<ProviderWriteState> states);

    List<ProviderWriteOutbox> findByUserIdAndProviderAndStateInOrderByCreatedAtAsc(
            UUID userId,
            SyncProvider provider,
            Collection<ProviderWriteState> states
    );

    java.util.Optional<ProviderWriteOutbox> findFirstByUserIdAndLocalTypeAndLocalIdAndProviderAndStateInOrderByCreatedAtAsc(
            UUID userId,
            com.timetable.operator.sync.domain.SyncMappingLocalType localType,
            UUID localId,
            SyncProvider provider,
            Collection<ProviderWriteState> states
    );
}
