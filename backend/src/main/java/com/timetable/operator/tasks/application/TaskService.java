package com.timetable.operator.tasks.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public List<Task> listTasks(String status, Instant dueBefore, UUID goalId, boolean unassigned) {
        AppUser user = currentUserProvider.getCurrentUser();
        List<Task> tasks = new ArrayList<>(taskRepository.findByUserIdOrderByPriorityAscDueDateAsc(user.getId()));
        return tasks.stream()
                .filter(task -> task.getSyncState() != PlannerSyncState.DETACHED)
                .filter(task -> status == null || task.getStatus().name().equalsIgnoreCase(status))
                .filter(task -> dueBefore == null || (task.getDueDate() != null && !task.getDueDate().isAfter(dueBefore)))
                .filter(task -> goalId == null || goalId.equals(task.getGoalId()))
                .filter(task -> !unassigned || task.getEventId() == null)
                .toList();
    }

    @Transactional
    public Task createTask(TaskWriteRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Task task = new Task();
        task.setUserId(user.getId());
        applyWritableFields(task, request);
        task.setSourceType(TaskSourceType.LOCAL);
        task.setSyncState(PlannerSyncState.LOCAL_ONLY);
        return taskRepository.save(task);
    }

    @Transactional
    public Task updateTask(UUID id, TaskWriteRequest request) {
        Task editable = getEditableTask(id);
        applyWritableFields(editable, request);
        return taskRepository.save(editable);
    }

    @Transactional
    public Task deleteTask(UUID id) {
        Task editable = getEditableTask(id);
        editable.setStatus(TaskStatus.CANCELLED);
        return taskRepository.save(editable);
    }

    @Transactional
    public Task completeTask(UUID id, CompleteTaskRequest request) {
        Task editable = getEditableTask(id);
        editable.setActualMinutes(request.actualMinutes());
        editable.setStatus(TaskStatus.DONE);
        return taskRepository.save(editable);
    }

    @Transactional
    public Task scheduleTask(UUID id, ScheduleTaskRequest request) {
        Task editable = getEditableTask(id);
        if (!request.endAt().isAfter(request.startAt())) {
            throw new IllegalArgumentException("태스크 배치 종료 시각은 시작 시각보다 늦어야 합니다.");
        }

        Event event = new Event();
        event.setUserId(editable.getUserId());
        event.setGoalId(editable.getGoalId());
        event.setTitle(editable.getTitle());
        event.setDescription(editable.getDescription());
        event.setStartAt(request.startAt());
        event.setEndAt(request.endAt());
        event.setPriority(editable.getPriority());
        event.setStatus(EventStatus.PLANNED);
        event.setCategory(request.category() == null ? ScheduleCategory.GROWTH : request.category());
        event.setSourceType(EventSourceType.LOCAL);
        event.setSyncState(PlannerSyncState.LOCAL_ONLY);
        Event savedEvent = eventRepository.save(event);

        editable.setEventId(savedEvent.getId());
        editable.setStatus(TaskStatus.SCHEDULED);
        return taskRepository.save(editable);
    }

    private Task getEditableTask(UUID id) {
        AppUser user = currentUserProvider.getCurrentUser();
        Task task = taskRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 태스크를 찾을 수 없습니다."));
        return ensureForkedWritable(task);
    }

    private Task ensureForkedWritable(Task task) {
        if (task.getSyncState() != PlannerSyncState.IMPORTED) {
            return task;
        }

        Task forked = new Task();
        forked.setUserId(task.getUserId());
        forked.setGoalId(task.getGoalId());
        forked.setEventId(task.getEventId());
        forked.setTitle(task.getTitle());
        forked.setDescription(task.getDescription());
        forked.setDueDate(task.getDueDate());
        forked.setEstimatedMinutes(task.getEstimatedMinutes());
        forked.setActualMinutes(task.getActualMinutes());
        forked.setPriority(task.getPriority());
        forked.setStatus(task.getStatus());
        forked.setCategory(task.getCategory());
        forked.setSourceType(TaskSourceType.LOCAL);
        forked.setSyncState(PlannerSyncState.FORKED);
        forked.setForkedFromTaskId(task.getId());
        forked.setExternalSourceId(task.getExternalSourceId());
        forked.setExternalEtag(task.getExternalEtag());
        forked.setLastSyncedAt(task.getLastSyncedAt());

        task.setSyncState(PlannerSyncState.DETACHED);
        taskRepository.save(task);
        return taskRepository.save(forked);
    }

    private void applyWritableFields(Task task, TaskWriteRequest request) {
        task.setTitle(request.title().trim());
        task.setDescription(blankToNull(request.description()));
        task.setDueDate(request.dueDate());
        task.setEstimatedMinutes(request.estimatedMinutes());
        task.setPriority(request.priority());
        task.setGoalId(request.goalId());
        task.setCategory(blankToNull(request.category()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record TaskWriteRequest(
            @NotBlank String title,
            String description,
            Instant dueDate,
            @Min(0) int estimatedMinutes,
            @Min(1) @Max(5) short priority,
            UUID goalId,
            String category
    ) {
    }

    public record CompleteTaskRequest(
            @NotNull Instant completedAt,
            @Min(0) Integer actualMinutes
    ) {
    }

    public record ScheduleTaskRequest(
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            ScheduleCategory category
    ) {
    }
}
