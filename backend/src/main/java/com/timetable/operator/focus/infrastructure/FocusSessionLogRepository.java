package com.timetable.operator.focus.infrastructure;

import com.timetable.operator.focus.domain.FocusSessionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FocusSessionLogRepository extends JpaRepository<FocusSessionLog, UUID> {

    List<FocusSessionLog> findByUserIdOrderByStartedAtDesc(UUID userId);

    List<FocusSessionLog> findByUserIdAndEventIdOrderByStartedAtDesc(UUID userId, UUID eventId);

    List<FocusSessionLog> findByUserIdAndTaskIdOrderByStartedAtDesc(UUID userId, UUID taskId);
}
