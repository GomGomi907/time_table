package com.timetable.operator.agent.infrastructure;

import com.timetable.operator.agent.domain.ChatCommandLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatCommandLogRepository extends JpaRepository<ChatCommandLog, UUID> {

    List<ChatCommandLog> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);
}
