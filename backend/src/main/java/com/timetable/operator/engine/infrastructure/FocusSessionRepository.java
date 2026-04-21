package com.timetable.operator.engine.infrastructure;

import com.timetable.operator.engine.domain.FocusSession;
import com.timetable.operator.engine.domain.FocusSessionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FocusSessionRepository extends JpaRepository<FocusSession, UUID> {
    Optional<FocusSession> findByUserIdAndStatus(UUID userId, FocusSessionStatus status);
}
