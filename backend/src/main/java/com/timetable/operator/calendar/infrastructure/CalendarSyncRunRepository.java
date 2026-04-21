package com.timetable.operator.calendar.infrastructure;

import com.timetable.operator.calendar.domain.CalendarSyncRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarSyncRunRepository extends JpaRepository<CalendarSyncRun, UUID> {

    Optional<CalendarSyncRun> findTopByUserIdOrderByStartedAtDesc(UUID userId);
}
