package com.timetable.operator.tasks.infrastructure;

import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    List<Task> findByUserIdOrderByPriorityAscDueDateAsc(UUID userId);

    List<Task> findByUserIdOrderByDueDateAsc(UUID userId);

    List<Task> findByUserIdAndStatusOrderByPriorityAscDueDateAsc(UUID userId, TaskStatus status);

    List<Task> findByUserIdAndStatusInOrderByPriorityAscDueDateAsc(UUID userId, List<TaskStatus> statuses);

    List<Task> findByUserIdAndStatusInAndDueDateBetweenOrderByDueDateAsc(
            UUID userId,
            List<TaskStatus> statuses,
            Instant from,
            Instant to
    );

    List<Task> findByUserIdAndGoalIdOrderByDueDateAsc(UUID userId, UUID goalId);

    List<Task> findByUserIdAndEventId(UUID userId, UUID eventId);

    List<Task> findByUserIdAndEventIdIsNullOrderByPriorityAscDueDateAsc(UUID userId);

    Optional<Task> findByUserIdAndExternalSourceId(UUID userId, String externalSourceId);
}
