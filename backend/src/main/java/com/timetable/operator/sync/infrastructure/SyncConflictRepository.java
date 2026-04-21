package com.timetable.operator.sync.infrastructure;

import com.timetable.operator.sync.domain.SyncConflict;
import com.timetable.operator.sync.domain.SyncConflictStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncConflictRepository extends JpaRepository<SyncConflict, UUID> {

    Optional<SyncConflict> findByIdAndUserId(UUID id, UUID userId);

    List<SyncConflict> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, SyncConflictStatus status);

    long countByUserIdAndStatus(UUID userId, SyncConflictStatus status);
}
