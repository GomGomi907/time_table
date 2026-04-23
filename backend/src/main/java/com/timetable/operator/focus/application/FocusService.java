package com.timetable.operator.focus.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.focus.domain.FocusCompletionType;
import com.timetable.operator.focus.domain.FocusSessionLog;
import com.timetable.operator.focus.domain.FocusTriggerSource;
import com.timetable.operator.focus.infrastructure.FocusSessionLogRepository;
import com.timetable.operator.goals.domain.Goal;
import com.timetable.operator.goals.infrastructure.GoalRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FocusService {

    private static final List<TaskStatus> RECOMMENDABLE_TASK_STATUSES = List.of(TaskStatus.TODO, TaskStatus.SCHEDULED);

    private final CurrentUserProvider currentUserProvider;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final GoalRepository goalRepository;
    private final FocusSessionLogRepository focusSessionLogRepository;

    @Transactional(readOnly = true)
    public FocusCurrentView getCurrentFocus() {
        AppUser user = currentUserProvider.getCurrentUser();
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Instant dayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));

        List<Event> todayEvents = eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                user.getId(),
                EventStatus.CANCELLED,
                dayEnd,
                dayStart
        ).stream().filter(event -> event.getSyncState() != PlannerSyncState.DETACHED).toList();

        List<Event> currentEvents = todayEvents.stream()
                .filter(event -> !event.getStartAt().isAfter(now) && event.getEndAt().isAfter(now))
                .toList();
        Event nextEvent = todayEvents.stream()
                .filter(event -> event.getStartAt().isAfter(now))
                .min(Comparator.comparing(Event::getStartAt))
                .orElse(null);
        List<Task> activeTasks = taskRepository.findByUserIdAndStatusOrderByPriorityAscDueDateAsc(
                user.getId(),
                TaskStatus.IN_PROGRESS
        ).stream()
                .filter(task -> task.getEventId() == null && task.getSyncState() != PlannerSyncState.DETACHED)
                .toList();
        List<Task> recommended = taskRepository.findByUserIdAndStatusInOrderByPriorityAscDueDateAsc(
                user.getId(),
                RECOMMENDABLE_TASK_STATUSES
        ).stream().filter(task -> task.getEventId() == null && task.getSyncState() != PlannerSyncState.DETACHED)
                .limit(3)
                .toList();

        if (currentEvents.size() > 1) {
            return new FocusCurrentView(
                    "RESCHEDULE_PENDING",
                    null,
                    nextEvent == null ? null : toNextItem(nextEvent),
                    recommended.stream().map(this::toRecommendedTask).toList(),
                    null,
                    null
            );
        }

        if (currentEvents.size() == 1) {
            Event event = currentEvents.get(0);
            Instant effectiveEnd = event.getActualEndAt() == null ? event.getEndAt() : event.getActualEndAt();
            String state = now.isAfter(event.getEndAt()) ? "AWAITING_END_CONFIRMATION" : "ACTIVE_EVENT";
            long remainingMinutes = Math.max(0, Duration.between(now, effectiveEnd).toMinutes());
            return new FocusCurrentView(
                    state,
                    toCurrentItem(event),
                    nextEvent == null ? null : toNextItem(nextEvent),
                    recommended.stream().map(this::toRecommendedTask).toList(),
                    null,
                    remainingMinutes
            );
        }

        if (!activeTasks.isEmpty()) {
            Task task = activeTasks.getFirst();
            ActiveTaskWindow taskWindow = resolveTaskWindow(user, task, nextEvent, now);
            return new FocusCurrentView(
                    "ACTIVE_TASK",
                    toCurrentTaskItem(task, taskWindow),
                    nextEvent == null ? null : toNextItem(nextEvent),
                    recommended.stream()
                            .filter(recommendedTask -> !recommendedTask.getId().equals(task.getId()))
                            .map(this::toRecommendedTask)
                            .toList(),
                    null,
                    taskWindow.remainingMinutes()
            );
        }

        if (nextEvent != null && !nextEvent.getStartAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            return new FocusCurrentView(
                    "UPCOMING_EVENT_READY",
                    null,
                    toNextItem(nextEvent),
                    recommended.stream().map(this::toRecommendedTask).toList(),
                    null,
                    Math.max(0, Duration.between(now, nextEvent.getStartAt()).toMinutes())
            );
        }

        if (!recommended.isEmpty()) {
            return new FocusCurrentView(
                    "NO_ACTIVE_ITEM",
                    null,
                    nextEvent == null ? null : toNextItem(nextEvent),
                    recommended.stream().map(this::toRecommendedTask).toList(),
                    null,
                    nextEvent == null ? null : Math.max(0, Duration.between(now, nextEvent.getStartAt()).toMinutes())
            );
        }

        return new FocusCurrentView(
                "NO_ACTIVE_ITEM",
                null,
                nextEvent == null ? null : toNextItem(nextEvent),
                List.of(),
                null,
                null
        );
    }

    @Transactional
    public FocusCurrentView startRecommendedTask(UUID taskId) {
        AppUser user = currentUserProvider.getCurrentUser();
        Task task = taskRepository.findByIdAndUserId(taskId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("추천 태스크를 찾을 수 없습니다."));
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        FocusSessionLog log = new FocusSessionLog();
        log.setUserId(user.getId());
        log.setTaskId(task.getId());
        log.setStartedAt(Instant.now());
        log.setTriggerSource(FocusTriggerSource.MANUAL);
        focusSessionLogRepository.save(log);
        return getCurrentFocus();
    }

    @Transactional
    public FocusCurrentView completeCurrent(CompleteFocusRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        if ("event".equalsIgnoreCase(request.itemType())) {
            Event event = eventRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("집중 대상 이벤트를 찾을 수 없습니다."));
            event.setActualEndAt(request.completedAt());
            event.setStatus(EventStatus.COMPLETED);
            eventRepository.save(event);

            FocusSessionLog log = new FocusSessionLog();
            log.setUserId(user.getId());
            log.setEventId(event.getId());
            log.setStartedAt(event.getActualStartAt() == null ? event.getStartAt() : event.getActualStartAt());
            log.setEndedAt(request.completedAt());
            log.setCompletionType(parseCompletion(request.completionType()));
            log.setTriggerSource(FocusTriggerSource.MANUAL);
            focusSessionLogRepository.save(log);
        }
        if ("task".equalsIgnoreCase(request.itemType())) {
            Task task = taskRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("집중 대상 태스크를 찾을 수 없습니다."));
            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(request.completedAt());
            taskRepository.save(task);
            closeLatestTaskLog(user.getId(), task.getId(), request.completedAt(), parseCompletion(request.completionType()), null);
        }
        return getCurrentFocus();
    }

    @Transactional
    public FocusCurrentView postponeCurrent(PostponeFocusRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        if ("event".equalsIgnoreCase(request.itemType())) {
            Event event = eventRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("집중 대상 이벤트를 찾을 수 없습니다."));
            event.setStatus(EventStatus.POSTPONED);
            eventRepository.save(event);

            FocusSessionLog log = new FocusSessionLog();
            log.setUserId(user.getId());
            log.setEventId(event.getId());
            log.setStartedAt(event.getActualStartAt() == null ? Instant.now() : event.getActualStartAt());
            log.setEndedAt(Instant.now());
            log.setCompletionType(FocusCompletionType.POSTPONED);
            log.setTriggerSource(FocusTriggerSource.MANUAL);
            log.setMemo(request.reason());
            focusSessionLogRepository.save(log);
        }
        return getCurrentFocus();
    }

    @Transactional
    public FocusCurrentView confirmOverrun(ConfirmOverrunRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        if (!"continue".equalsIgnoreCase(request.action())) {
            return getCurrentFocus();
        }

        Event event = eventRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                .orElseThrow(() -> new IllegalArgumentException("집중 대상 이벤트를 찾을 수 없습니다."));
        event.setEndAt(event.getEndAt().plus(Duration.ofMinutes(request.expectedExtraMinutes())));
        event.setStatus(EventStatus.ACTIVE);
        eventRepository.save(event);
        return getCurrentFocus();
    }

    private FocusCompletionType parseCompletion(String completionType) {
        return switch (completionType == null ? "" : completionType.trim().toLowerCase()) {
            case "early" -> FocusCompletionType.EARLY;
            case "late" -> FocusCompletionType.LATE;
            case "postponed" -> FocusCompletionType.POSTPONED;
            case "interrupted" -> FocusCompletionType.INTERRUPTED;
            case "skipped" -> FocusCompletionType.SKIPPED;
            default -> FocusCompletionType.ON_TIME;
        };
    }

    private CurrentItem toCurrentItem(Event event) {
        Goal goal = event.getGoalId() == null ? null : goalRepository.findById(event.getGoalId()).orElse(null);
        return new CurrentItem(
                "event",
                event.getId().toString(),
                event.getTitle(),
                event.getStartAt(),
                event.getEndAt(),
                Math.max(0, Duration.between(Instant.now(), event.getEndAt()).toMinutes()),
                event.getPriority(),
                goal == null ? null : new GoalSummary(goal.getId().toString(), goal.getTitle())
        );
    }

    private CurrentItem toCurrentTaskItem(Task task, ActiveTaskWindow taskWindow) {
        Goal goal = task.getGoalId() == null ? null : goalRepository.findById(task.getGoalId()).orElse(null);
        return new CurrentItem(
                "task",
                task.getId().toString(),
                task.getTitle(),
                taskWindow.startAt(),
                taskWindow.endAt(),
                taskWindow.remainingMinutes(),
                task.getPriority(),
                goal == null ? null : new GoalSummary(goal.getId().toString(), goal.getTitle())
        );
    }

    private NextItem toNextItem(Event event) {
        return new NextItem("event", event.getId().toString(), event.getTitle(), event.getStartAt());
    }

    private RecommendedTask toRecommendedTask(Task task) {
        return new RecommendedTask(
                task.getId().toString(),
                task.getTitle(),
                task.getPriority(),
                task.getEstimatedMinutes(),
                task.getDueDate()
        );
    }

    private ActiveTaskWindow resolveTaskWindow(AppUser user, Task task, Event nextEvent, Instant now) {
        Instant startedAt = focusSessionLogRepository.findByUserIdAndTaskIdOrderByStartedAtDesc(user.getId(), task.getId()).stream()
                .findFirst()
                .map(FocusSessionLog::getStartedAt)
                .orElse(task.getUpdatedAt());
        long plannedMinutes = Math.max(task.getEstimatedMinutes(), 30);
        Instant plannedEndAt = startedAt.plus(Duration.ofMinutes(plannedMinutes));
        Instant effectiveEndAt = nextEvent != null && nextEvent.getStartAt().isBefore(plannedEndAt)
                ? nextEvent.getStartAt()
                : plannedEndAt;
        long remainingMinutes = Math.max(0, Duration.between(now, effectiveEndAt).toMinutes());
        return new ActiveTaskWindow(startedAt, effectiveEndAt, remainingMinutes);
    }

    private void closeLatestTaskLog(
            UUID userId,
            UUID taskId,
            Instant endedAt,
            FocusCompletionType completionType,
            String memo
    ) {
        focusSessionLogRepository.findByUserIdAndTaskIdOrderByStartedAtDesc(userId, taskId).stream()
                .filter(log -> log.getEndedAt() == null)
                .findFirst()
                .ifPresent(log -> {
                    log.setEndedAt(endedAt);
                    log.setCompletionType(completionType);
                    log.setMemo(memo);
                    focusSessionLogRepository.save(log);
                });
    }

    public record CompleteFocusRequest(
            @NotBlank String itemType,
            @NotBlank String itemId,
            @NotNull Instant completedAt,
            String completionType
    ) {
    }

    public record PostponeFocusRequest(
            @NotBlank String itemType,
            @NotBlank String itemId,
            String reason,
            boolean requestAiReschedule
    ) {
    }

    public record ConfirmOverrunRequest(
            @NotBlank String itemType,
            @NotBlank String itemId,
            @NotBlank String action,
            int expectedExtraMinutes
    ) {
    }

    public record FocusCurrentView(
            String focusState,
            CurrentItem currentItem,
            NextItem nextItem,
            List<RecommendedTask> recommendedTasks,
            Object activeSuggestion,
            Long remainingMinutes
    ) {
    }

    public record CurrentItem(
            String type,
            String id,
            String title,
            Instant startAt,
            Instant endAt,
            long remainingMinutes,
            short priority,
            GoalSummary goal
    ) {
    }

    public record GoalSummary(
            String id,
            String title
    ) {
    }

    public record NextItem(
            String type,
            String id,
            String title,
            Instant startAt
    ) {
    }

    public record RecommendedTask(
            String id,
            String title,
            short priority,
            int estimatedMinutes,
            Instant dueDate
    ) {
    }

    private record ActiveTaskWindow(
            Instant startAt,
            Instant endAt,
            long remainingMinutes
    ) {
    }
}
