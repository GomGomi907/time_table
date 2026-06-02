package com.timetable.operator.agent.infrastructure;

import com.timetable.operator.agent.domain.RescheduleSuggestion;
import com.timetable.operator.agent.domain.RescheduleSuggestionStatus;
import com.timetable.operator.agent.domain.RescheduleSuggestionTriggerType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RescheduleSuggestionRepository extends JpaRepository<RescheduleSuggestion, UUID> {

    List<RescheduleSuggestion> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<RescheduleSuggestion> findByUserIdAndStatusInOrderByCreatedAtDesc(
            UUID userId,
            Collection<RescheduleSuggestionStatus> statuses,
            Pageable pageable
    );

    Optional<RescheduleSuggestion> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select suggestion from RescheduleSuggestion suggestion where suggestion.id = :id and suggestion.userId = :userId")
    Optional<RescheduleSuggestion> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<RescheduleSuggestion> findTopByUserIdAndTriggerTypeOrderByCreatedAtDesc(
            UUID userId,
            RescheduleSuggestionTriggerType triggerType
    );
}
