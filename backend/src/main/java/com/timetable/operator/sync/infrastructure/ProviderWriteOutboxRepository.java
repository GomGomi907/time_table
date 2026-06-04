package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.ProviderWriteOutbox;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncProvider;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderWriteOutboxRepository extends JpaRepository<ProviderWriteOutbox, UUID> {

    long countByUserIdAndStateIn(UUID userId, Collection<ProviderWriteState> states);

    @Query("""
            select outbox
            from ProviderWriteOutbox outbox
            where outbox.userId = :userId
              and outbox.provider = :provider
              and outbox.state in :states
              and (outbox.nextRetryAt is null or outbox.nextRetryAt <= :now)
            order by outbox.createdAt asc
            """)
    List<ProviderWriteOutbox> findReadyByUserIdAndProviderAndStateInOrderByCreatedAtAsc(
            @Param("userId") UUID userId,
            @Param("provider") SyncProvider provider,
            @Param("states") Collection<ProviderWriteState> states,
            @Param("now") Instant now
    );

    @Query("""
            select count(outbox)
            from ProviderWriteOutbox outbox
            where outbox.userId = :userId
              and outbox.provider = :provider
              and outbox.state in :states
              and outbox.nextRetryAt is not null
              and outbox.nextRetryAt > :now
            """)
    long countWaitingRetryByUserIdAndProviderAndStateIn(
            @Param("userId") UUID userId,
            @Param("provider") SyncProvider provider,
            @Param("states") Collection<ProviderWriteState> states,
            @Param("now") Instant now
    );

    java.util.Optional<ProviderWriteOutbox> findFirstByUserIdAndLocalTypeAndLocalIdAndProviderAndStateInOrderByCreatedAtAsc(
            UUID userId,
            com.timetable.operator.sync.domain.SyncMappingLocalType localType,
            UUID localId,
            SyncProvider provider,
            Collection<ProviderWriteState> states
    );
}
