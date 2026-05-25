package com.timetable.operator.tasks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ProviderWriteOutboxService providerWriteOutboxService;

    @InjectMocks
    private TaskService taskService;

    @Test
    void importedTaskCompletionKeepsOriginalRowAndEnqueuesProviderWrite() {
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AppUser user = user(userId);
        Task imported = importedTask(userId, taskId);

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(imported));
        when(taskRepository.save(same(imported))).thenReturn(imported);

        Task result = taskService.completeTask(taskId, new TaskService.CompleteTaskRequest(
                Instant.parse("2026-05-15T03:00:00Z"),
                35
        ));

        assertThat(result).isSameAs(imported);
        assertThat(result.getId()).isEqualTo(taskId);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(result.getCompletedAt()).isEqualTo(Instant.parse("2026-05-15T03:00:00Z"));
        assertThat(result.getSyncState()).isEqualTo(PlannerSyncState.IMPORTED);
        verify(providerWriteOutboxService).enqueueTaskWrite(imported, ProviderWriteOperation.UPDATE);
        verify(taskRepository).save(same(imported));
    }

    private AppUser user(UUID userId) {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        user.setProvider("local");
        return user;
    }

    private Task importedTask(UUID userId, UUID taskId) {
        Task task = new Task();
        task.setId(taskId);
        task.setUserId(userId);
        task.setTitle("Write brief");
        task.setEstimatedMinutes(30);
        task.setPriority((short) 3);
        task.setStatus(TaskStatus.TODO);
        task.setSyncState(PlannerSyncState.IMPORTED);
        task.setExternalSourceId("google_tasks:list-1:task-1");
        task.setExternalEtag("\"task-etag-1\"");
        return task;
    }
}
