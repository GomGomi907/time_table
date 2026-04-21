package com.timetable.operator.events.infrastructure;

import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByIdAndUserId(UUID id, UUID userId);

    List<Event> findByUserIdOrderByStartAtAsc(UUID userId);

    List<Event> findByUserIdAndStartAtBetweenOrderByStartAtAsc(UUID userId, Instant from, Instant to);

    List<Event> findByUserIdAndStatusOrderByStartAtAsc(UUID userId, EventStatus status);

    List<Event> findByUserIdAndStatusNotAndStartAtBetweenOrderByStartAtAsc(
            UUID userId,
            EventStatus status,
            Instant from,
            Instant to
    );

    List<Event> findByUserIdAndGoalIdOrderByStartAtAsc(UUID userId, UUID goalId);

    Optional<Event> findByUserIdAndExternalSourceId(UUID userId, String externalSourceId);
}
