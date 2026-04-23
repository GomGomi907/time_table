package com.timetable.operator.agent.infrastructure;

import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RescheduleSuggestionRepository extends JpaRepository<RescheduleSuggestion, UUID> {

    List<RescheduleSuggestion> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<RescheduleSuggestion> findByIdAndUserId(UUID id, UUID userId);

    Optional<RescheduleSuggestion> findTopByUserIdAndTriggerTypeOrderByCreatedAtDesc(
            UUID userId,
            RescheduleSuggestionTriggerType triggerType
    );
}
