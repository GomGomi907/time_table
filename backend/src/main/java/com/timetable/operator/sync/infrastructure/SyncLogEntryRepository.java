package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.SyncLogEntry;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncLogEntryRepository extends JpaRepository<SyncLogEntry, UUID> {

    Optional<SyncLogEntry> findTopByUserIdAndTargetSystemOrderByCreatedAtDesc(UUID userId, SyncTargetSystem targetSystem);

    List<SyncLogEntry> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);
}
