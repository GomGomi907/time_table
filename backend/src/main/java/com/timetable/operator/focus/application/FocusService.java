package com.timetable.operator.focus.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.agent.application.RescheduleSuggestionService;
import com.timetable.operator.common.config.AppProperties;
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
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.application.ScheduleBlockRules;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.settings.domain.UserPreferences;
import com.timetable.operator.settings.infrastructure.UserPreferencesRepository;
import com.timetable.operator.sync.application.ProviderWriteOutboxService;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final ScheduleBlockRules scheduleBlockRules;
    private final UserPreferencesRepository userPreferencesRepository;
    private final AppProperties appProperties;
    private final RescheduleSuggestionService rescheduleSuggestionService;
    private final ProviderWriteOutboxService providerWriteOutboxService;

    @Transactional(readOnly = true)
    public FocusCurrentView getCurrentFocus() {
        AppUser user = currentUserProvider.getCurrentUser();
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        Instant dayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));
        ScheduleContext scheduleContext = buildScheduleContext(user, zoneId);
        FocusPreferenceContext preferenceContext = buildPreferenceContext(user);

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
                .toList();
        recommended = uniqueRecommendedTasks(recommended, 3);
        Map<UUID, Goal> goalsById = loadGoals(todayEvents, activeTasks);

        if (currentEvents.size() > 1) {
            return new FocusCurrentView(
                    "RESCHEDULE_PENDING",
                    null,
                    nextEvent == null ? null : toNextItem(nextEvent),
                    scheduleContext,
                    preferenceContext,
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
                    toCurrentItem(event, goalsById),
                    nextEvent == null ? null : toNextItem(nextEvent),
                    scheduleContext,
                    preferenceContext,
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
                    toCurrentTaskItem(task, taskWindow, goalsById),
                    nextEvent == null ? null : toNextItem(nextEvent),
                    scheduleContext,
                    preferenceContext,
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
                    scheduleContext,
                    preferenceContext,
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
                    scheduleContext,
                    preferenceContext,
                    recommended.stream().map(this::toRecommendedTask).toList(),
                    null,
                    nextEvent == null ? null : Math.max(0, Duration.between(now, nextEvent.getStartAt()).toMinutes())
            );
        }

        return new FocusCurrentView(
                "NO_ACTIVE_ITEM",
                null,
                nextEvent == null ? null : toNextItem(nextEvent),
                scheduleContext,
                preferenceContext,
                List.of(),
                null,
                null
        );
    }

    @Transactional
    public FocusCurrentView startRecommendedTask(UUID taskId) {
        AppUser user = currentUserProvider.getCurrentUser();
        Task task = taskRepository.findByIdAndUserId(taskId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("추천 할 일을 찾을 수 없습니다."));
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
            providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
            eventRepository.save(event);

            FocusSessionLog log = new FocusSessionLog();
            log.setUserId(user.getId());
            log.setEventId(event.getId());
            log.setStartedAt(event.getActualStartAt() == null ? event.getStartAt() : event.getActualStartAt());
            log.setEndedAt(request.completedAt());
            log.setCompletionType(parseCompletion(request.completionType()));
            log.setTriggerSource(FocusTriggerSource.MANUAL);
            focusSessionLogRepository.save(log);
        } else if ("task".equalsIgnoreCase(request.itemType())) {
            Task task = taskRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("집중 대상 할 일을 찾을 수 없습니다."));
            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(request.completedAt());
            providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
            taskRepository.save(task);
            closeLatestTaskLog(user.getId(), task.getId(), request.completedAt(), parseCompletion(request.completionType()), null);
        } else {
            throw new IllegalArgumentException("지원하지 않는 집중 대상 타입입니다: " + request.itemType());
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
            providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
            eventRepository.save(event);
            requestAiRescheduleIfNeeded(
                    request,
                    "미룬 일정 \"%s\"의 빈 시간과 다음 충돌을 다시 정리해 주세요.".formatted(event.getTitle())
            );

            FocusSessionLog log = new FocusSessionLog();
            log.setUserId(user.getId());
            log.setEventId(event.getId());
            log.setStartedAt(event.getActualStartAt() == null ? Instant.now() : event.getActualStartAt());
            log.setEndedAt(Instant.now());
            log.setCompletionType(FocusCompletionType.POSTPONED);
            log.setTriggerSource(FocusTriggerSource.MANUAL);
            log.setMemo(request.reason());
            focusSessionLogRepository.save(log);
        } else if ("task".equalsIgnoreCase(request.itemType())) {
            Task task = taskRepository.findByIdAndUserId(UUID.fromString(request.itemId()), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("집중 대상 할 일을 찾을 수 없습니다."));
            task.setStatus(TaskStatus.DEFERRED);
            providerWriteOutboxService.enqueueTaskWrite(task, ProviderWriteOperation.UPDATE);
            taskRepository.save(task);
            requestAiRescheduleIfNeeded(
                    request,
                    "미룬 할 일 \"%s\"를 오늘 남은 시간에 다시 배치해 주세요.".formatted(task.getTitle())
            );
            closeLatestTaskLog(
                    user.getId(),
                    task.getId(),
                    Instant.now(),
                    FocusCompletionType.POSTPONED,
                    request.reason()
            );
        } else {
            throw new IllegalArgumentException("지원하지 않는 집중 대상 타입입니다: " + request.itemType());
        }
        return getCurrentFocus();
    }

    @Transactional
    public FocusCurrentView completeScheduleBlock(UUID blockId) {
        AppUser user = currentUserProvider.getCurrentUser();
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(blockId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("완료할 일정 블록을 찾을 수 없습니다."));
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalTime nowTime = LocalTime.now(zoneId).withSecond(0).withNano(0);
        DayOfWeek today = LocalDate.now(zoneId).getDayOfWeek();

        if (!isActiveScheduleBlock(block, today, today.minus(1), nowTime)) {
            throw new IllegalArgumentException("현재 진행 중인 일정 블록만 완료할 수 있습니다.");
        }

        block.setEndTime(resolveScheduleBlockCompletionEnd(block, nowTime));
        block.setSourceRef("focus-complete");
        scheduleBlockRules.validateForUser(user.getId(), block);
        scheduleBlockRepository.save(block);
        return getCurrentFocus();
    }

    @Transactional
    public FocusCurrentView postponeScheduleBlock(UUID blockId, PostponeScheduleBlockRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        ScheduleBlock block = scheduleBlockRepository.findByIdAndUserId(blockId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("미룰 일정 블록을 찾을 수 없습니다."));
        ScheduleBlockRules.ShiftedScheduleBlock shiftedBlock = scheduleBlockRules.shift(
                block.getDayOfWeek(),
                block.getStartTime(),
                block.getEndTime(),
                30
        );
        block.setDayOfWeek(shiftedBlock.dayOfWeek());
        block.setStartTime(shiftedBlock.startTime());
        block.setEndTime(shiftedBlock.endTime());
        block.setSourceRef("focus-postpone");
        scheduleBlockRules.validateForUser(user.getId(), block);
        scheduleBlockRepository.save(block);

        if (request.requestAiReschedule()) {
            String reason = (request.reason() == null || request.reason().isBlank())
                    ? "실행 모드에서 일정 블록 \"%s\"을 30분 미뤘습니다. 남은 일정을 다시 확인해 주세요.".formatted(block.getActivity())
                    : request.reason().trim();
            rescheduleSuggestionService.createManualSuggestion(
                    new RescheduleSuggestionService.ManualRescheduleRequest(
                            "postpone",
                            null,
                            null,
                            reason
                    )
            );
        }

        return getCurrentFocus();
    }

    private void requestAiRescheduleIfNeeded(PostponeFocusRequest request, String fallbackReason) {
        if (!request.requestAiReschedule()) {
            return;
        }

        String reason = (request.reason() == null || request.reason().isBlank())
                ? fallbackReason
                : "%s\n%s".formatted(request.reason().trim(), fallbackReason);
        rescheduleSuggestionService.createManualSuggestion(
                new RescheduleSuggestionService.ManualRescheduleRequest(
                        "postpone",
                        null,
                        null,
                        reason
                )
        );
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
        providerWriteOutboxService.enqueueEventWrite(event, ProviderWriteOperation.UPDATE);
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

    private LocalTime resolveScheduleBlockCompletionEnd(ScheduleBlock block, LocalTime nowTime) {
        int elapsedMinutes = displayMinute(nowTime) - displayMinute(block.getStartTime());
        if (elapsedMinutes < 0) {
            elapsedMinutes = rawMinute(nowTime) - rawMinute(block.getStartTime());
        }
        if (elapsedMinutes < ScheduleBlockRules.MIN_BLOCK_DURATION_MINUTES) {
            return block.getStartTime().plusMinutes(ScheduleBlockRules.MIN_BLOCK_DURATION_MINUTES);
        }
        return nowTime;
    }

    private int displayMinute(LocalTime time) {
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        return time.isBefore(ScheduleBlockRules.OVERNIGHT_DISPLAY_CUTOFF)
                ? minuteOfDay + (24 * 60)
                : minuteOfDay;
    }

    private int rawMinute(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private Map<UUID, Goal> loadGoals(List<Event> events, List<Task> tasks) {
        Map<UUID, Goal> goalsById = new LinkedHashMap<>();
        for (Event event : events) {
            if (event.getGoalId() != null) {
                goalsById.putIfAbsent(event.getGoalId(), null);
            }
        }
        for (Task task : tasks) {
            if (task.getGoalId() != null) {
                goalsById.putIfAbsent(task.getGoalId(), null);
            }
        }
        if (goalsById.isEmpty()) {
            return Map.of();
        }
        goalRepository.findAllById(goalsById.keySet()).forEach(goal -> goalsById.put(goal.getId(), goal));
        return goalsById;
    }

    private CurrentItem toCurrentItem(Event event, Map<UUID, Goal> goalsById) {
        Goal goal = event.getGoalId() == null ? null : goalsById.get(event.getGoalId());
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

    private CurrentItem toCurrentTaskItem(Task task, ActiveTaskWindow taskWindow, Map<UUID, Goal> goalsById) {
        Goal goal = task.getGoalId() == null ? null : goalsById.get(task.getGoalId());
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

    private List<Task> uniqueRecommendedTasks(List<Task> tasks, int limit) {
        Map<String, Task> uniqueByUserVisibleTitle = new LinkedHashMap<>();
        for (Task task : tasks) {
            uniqueByUserVisibleTitle.putIfAbsent(recommendedTaskKey(task), task);
            if (uniqueByUserVisibleTitle.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(uniqueByUserVisibleTitle.values());
    }

    private String recommendedTaskKey(Task task) {
        return task.getTitle() == null
                ? ""
                : task.getTitle().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private FocusPreferenceContext buildPreferenceContext(AppUser user) {
        UserPreferences preferences = userPreferencesRepository.findByUserId(user.getId()).orElse(null);
        int preferredFocusMinutes = preferences == null
                ? 45
                : preferences.getPreferredFocusMinutes();
        int breakBufferMinutes = preferences == null
                ? appProperties.schedule().defaultBufferMinutes()
                : preferences.getBreakBufferMinutes();
        String interventionStyle = preferences == null
                ? "balanced"
                : normalizeInterventionStyle(preferences.getInterventionFrequency());

        return new FocusPreferenceContext(
                preferredFocusMinutes,
                breakBufferMinutes,
                interventionStyle,
                interventionLabel(interventionStyle),
                "추천 할 일은 %d분 집중 단위와 %d분 회복 버퍼를 기준으로 안내합니다."
                        .formatted(preferredFocusMinutes, breakBufferMinutes)
        );
    }

    private String normalizeInterventionStyle(String interventionFrequency) {
        if (interventionFrequency == null || interventionFrequency.isBlank()) {
            return "balanced";
        }
        return interventionFrequency.trim().toLowerCase();
    }

    private String interventionLabel(String interventionStyle) {
        return switch (interventionStyle) {
            case "minimal" -> "최소 개입";
            case "proactive" -> "적극 제안";
            default -> "균형 있게";
        };
    }

    private ScheduleContext buildScheduleContext(AppUser user, ZoneId zoneId) {
        List<ScheduleBlock> blocks = scheduleBlockRepository.findByUserId(user.getId());
        if (blocks.isEmpty()) {
            return new ScheduleContext("NO_BLOCK", null, null);
        }

        LocalTime nowTime = LocalTime.now(zoneId);
        DayOfWeek today = LocalDate.now(zoneId).getDayOfWeek();
        DayOfWeek yesterday = today.minus(1);

        ScheduleBlock currentBlock = blocks.stream()
                .filter(block -> isActiveScheduleBlock(block, today, yesterday, nowTime))
                .min(Comparator
                        .comparingInt((ScheduleBlock block) -> displayOrder(block.getStartTime()))
                        .thenComparing(ScheduleBlock::getEndTime))
                .orElse(null);
        ScheduleBlock nextBlock = findNextScheduleBlock(blocks, today, nowTime);

        String state = currentBlock != null ? "ACTIVE_BLOCK" : nextBlock != null ? "UPCOMING_BLOCK" : "NO_BLOCK";
        return new ScheduleContext(
                state,
                currentBlock == null ? null : toScheduleBlockItem(currentBlock),
                nextBlock == null ? null : toScheduleBlockItem(nextBlock)
        );
    }

    private ScheduleBlock findNextScheduleBlock(List<ScheduleBlock> blocks, DayOfWeek today, LocalTime nowTime) {
        return blocks.stream()
                .map(block -> new UpcomingScheduleBlock(block, minutesUntilBlockStart(block, today, nowTime)))
                .filter(candidate -> candidate.minutesUntilStart() > 0)
                .min(Comparator.comparingInt(UpcomingScheduleBlock::minutesUntilStart))
                .map(UpcomingScheduleBlock::block)
                .orElse(null);
    }

    private boolean isActiveScheduleBlock(
            ScheduleBlock block,
            DayOfWeek today,
            DayOfWeek yesterday,
            LocalTime nowTime
    ) {
        if (!crossesMidnight(block)) {
            return block.getDayOfWeek() == today
                    && !nowTime.isBefore(block.getStartTime())
                    && nowTime.isBefore(block.getEndTime());
        }

        return (block.getDayOfWeek() == today && !nowTime.isBefore(block.getStartTime()))
                || (block.getDayOfWeek() == yesterday && nowTime.isBefore(block.getEndTime()));
    }

    private int minutesUntilBlockStart(ScheduleBlock block, DayOfWeek today, LocalTime nowTime) {
        int dayOffset = Math.floorMod(block.getDayOfWeek().getValue() - today.getValue(), 7);
        int nowMinutes = nowTime.toSecondOfDay() / 60;
        int startMinutes = block.getStartTime().toSecondOfDay() / 60;
        int minutes = dayOffset * 24 * 60 + startMinutes - nowMinutes;
        if (minutes <= 0) {
            minutes += 7 * 24 * 60;
        }
        return minutes;
    }

    private boolean crossesMidnight(ScheduleBlock block) {
        return !block.getEndTime().isAfter(block.getStartTime());
    }

    private int displayOrder(LocalTime time) {
        int seconds = time.toSecondOfDay();
        return time.isBefore(LocalTime.of(5, 0)) ? seconds + (24 * 60 * 60) : seconds;
    }

    private ScheduleBlockItem toScheduleBlockItem(ScheduleBlock block) {
        return new ScheduleBlockItem(
                block.getId().toString(),
                block.getDayOfWeek().name(),
                block.getStartTime().toString(),
                block.getEndTime().toString(),
                block.getActivity(),
                block.getCategory().name(),
                block.getNote(),
                block.getSourceType().name(),
                block.getSourceRef()
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

    public record PostponeScheduleBlockRequest(
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
            ScheduleContext scheduleContext,
            FocusPreferenceContext preferenceContext,
            List<RecommendedTask> recommendedTasks,
            RescheduleSuggestionService.RescheduleSuggestionResponse activeSuggestion,
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

    public record ScheduleContext(
            String state,
            ScheduleBlockItem currentBlock,
            ScheduleBlockItem nextBlock
    ) {
    }

    public record FocusPreferenceContext(
            int preferredFocusMinutes,
            int breakBufferMinutes,
            String interventionStyle,
            String interventionLabel,
            String guidance
    ) {
    }

    public record ScheduleBlockItem(
            String id,
            String dayOfWeek,
            String startTime,
            String endTime,
            String activity,
            String category,
            String note,
            String sourceType,
            String sourceRef
    ) {
    }

    private record ActiveTaskWindow(
            Instant startAt,
            Instant endAt,
            long remainingMinutes
    ) {
    }

    private record UpcomingScheduleBlock(
            ScheduleBlock block,
            int minutesUntilStart
    ) {
    }
}
