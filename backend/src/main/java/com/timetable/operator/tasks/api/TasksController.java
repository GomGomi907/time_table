package com.timetable.operator.tasks.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.tasks.application.TaskService;
import com.timetable.operator.tasks.domain.Task;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TasksController {

    private final TaskService taskService;

    @GetMapping
    public ApiEnvelope<List<TaskResponse>> getTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant dueBefore,
            @RequestParam(required = false) UUID goalId,
            @RequestParam(defaultValue = "false") boolean unassigned
    ) {
        return ApiEnvelope.ok(taskService.listTasks(status, dueBefore, goalId, unassigned).stream()
                .map(TaskResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiEnvelope<TaskResponse> getTask(@PathVariable UUID id) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.getTask(id)));
    }

    @PostMapping
    public ApiEnvelope<TaskResponse> createTask(@Valid @RequestBody TaskService.TaskWriteRequest request) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.createTask(request)));
    }

    @PatchMapping("/{id}")
    public ApiEnvelope<TaskResponse> updateTask(
            @PathVariable UUID id,
            @Valid @RequestBody TaskService.TaskWriteRequest request
    ) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.updateTask(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiEnvelope<TaskResponse> deleteTask(@PathVariable UUID id) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.deleteTask(id)));
    }

    @PostMapping("/{id}/complete")
    public ApiEnvelope<TaskResponse> completeTask(
            @PathVariable UUID id,
            @Valid @RequestBody TaskService.CompleteTaskRequest request
    ) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.completeTask(id, request)));
    }

    @PostMapping("/{id}/schedule")
    public ApiEnvelope<TaskResponse> scheduleTask(
            @PathVariable UUID id,
            @Valid @RequestBody TaskService.ScheduleTaskRequest request
    ) {
        return ApiEnvelope.ok(TaskResponse.from(taskService.scheduleTask(id, request)));
    }

    public record TaskResponse(
            String id,
            String title,
            String description,
            Instant dueDate,
            int estimatedMinutes,
            Integer actualMinutes,
            short priority,
            String status,
            String category,
            String sourceType,
            String syncState,
            String goalId,
            String eventId,
            Instant completedAt
    ) {
        static TaskResponse from(Task task) {
            return new TaskResponse(
                    task.getId().toString(),
                    task.getTitle(),
                    task.getDescription(),
                    task.getDueDate(),
                    task.getEstimatedMinutes(),
                    task.getActualMinutes(),
                    task.getPriority(),
                    task.getStatus().name(),
                    task.getCategory(),
                    task.getSourceType().name(),
                    task.getSyncState().name(),
                    task.getGoalId() == null ? null : task.getGoalId().toString(),
                    task.getEventId() == null ? null : task.getEventId().toString(),
                    task.getCompletedAt()
            );
        }
    }
}
