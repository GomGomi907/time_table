package com.timetable.operator.goals.infrastructure;

import com.timetable.operator.goals.domain.Goal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    boolean existsByUserId(UUID userId);

    List<Goal> findByUserId(UUID userId);

    List<Goal> findByUserIdOrderByPriorityAscCreatedAtAsc(UUID userId);

    java.util.Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
}
