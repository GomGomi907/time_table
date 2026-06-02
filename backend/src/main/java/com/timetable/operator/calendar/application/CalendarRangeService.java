package com.timetable.operator.calendar.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.RoutineShadowOverride;
import com.timetable.operator.schedule.domain.RoutineShadowPolicy;
import com.timetable.operator.schedule.domain.RoutineShadowState;
import com.timetable.operator.schedule.infrastructure.RoutineShadowOverrideRepository;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.schedule.domain.ShadowingEntityType;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskStatus;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarRangeService {
    private static final int MAX_RANGE_DAYS = 45;
    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.TODO,
            TaskStatus.SCHEDULED,
            TaskStatus.IN_PROGRESS,
            TaskStatus.DEFERRED
    );

    private final CurrentUserProvider currentUserProvider;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final RoutineShadowOverrideRepository routineShadowOverrideRepository;

    @Transactional(readOnly = true)
    public CalendarRangeResponse getRange(Instant start, Instant end, CalendarView view, String requestedTimezone) {
        validateRange(start, end);
        AppUser user = currentUserProvider.getCurrentUser();
        ZoneId timezone = resolveTimezone(requestedTimezone, user.getTimezone());

        List<Event> events = eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                user.getId(),
                EventStatus.CANCELLED,
                end,
                start
        );
        List<Task> tasks = taskRepository.findByUserIdAndStatusInAndDueDateBetweenOrderByDueDateAsc(
                user.getId(),
                ACTIVE_TASK_STATUSES,
                start,
                end
        );
        LocalDate startDate = start.atZone(timezone).toLocalDate();
        LocalDate endDate = end.minusNanos(1).atZone(timezone).toLocalDate();
        Set<DayOfWeek> rangeDayOfWeeks = lookupDayOfWeeksBetween(startDate, endDate, Duration.between(start, end));
        List<ScheduleBlock> scheduleBlocks = scheduleBlockRepository.findByUserIdAndDayOfWeekIn(
                user.getId(),
                rangeDayOfWeeks
        );
        Map<RoutineShadowKey, RoutineShadowOverride> shadowOverrides = findShadowOverrides(
                user.getId(),
                scheduleBlocks,
                startDate,
                endDate
        );

        List<CalendarOccurrence> occurrences = new ArrayList<>();
        events.stream()
                .map(CalendarRangeService::fromEvent)
                .forEach(occurrences::add);
        tasks.stream()
                .map(CalendarRangeService::fromTask)
                .forEach(occurrences::add);
        occurrences.addAll(projectRoutineOccurrences(scheduleBlocks, shadowOverrides, start, end, timezone));
        occurrences.sort(Comparator.comparing(CalendarOccurrence::startAt)
                .thenComparing(CalendarOccurrence::priorityTier)
                .thenComparing(CalendarOccurrence::title));

        QueryInstrumentation instrumentation = new QueryInstrumentation(
                4,
                List.of("events", "tasks", "schedule_blocks", "routine_shadow_overrides"),
                occurrences.size(),
                (int) Duration.between(start, end).toDays()
        );
        return new CalendarRangeResponse(start, end, view, timezone.getId(), occurrences, instrumentation);
    }

    private static CalendarOccurrence fromEvent(Event event) {
        return new CalendarOccurrence(
                "event:" + event.getId(),
                CalendarEntityType.EVENT,
                event.getId().toString(),
                event.getRecurringEventId() == null ? event.getId().toString() : event.getRecurringEventId().toString(),
                event.getStartAt(),
                event.getEndAt(),
                event.getTitle(),
                event.getCategory().name(),
                event.getSourceType().name(),
                event.getSyncState(),
                event.getRecurrenceInstanceType().name(),
                PriorityTier.GOOGLE_EVENT_FIXED.value,
                CollisionPolicy.BLOCKS_LOWER,
                ProviderAuthority.REMOTE_FIXED,
                RoutineShadowState.NONE,
                null,
                null,
                false,
                event.getRrule() != null && !event.getRrule().isBlank()
        );
    }

    private static CalendarOccurrence fromTask(Task task) {
        Instant dueDate = task.getDueDate();
        return new CalendarOccurrence(
                "task:" + task.getId(),
                CalendarEntityType.TASK,
                task.getId().toString(),
                task.getId().toString(),
                dueDate,
                dueDate == null ? null : dueDate.plus(Duration.ofMinutes(Math.max(task.getEstimatedMinutes(), 30))),
                task.getTitle(),
                task.getCategory(),
                task.getSourceType().name(),
                task.getSyncState(),
                "SINGLE",
                PriorityTier.TASK_DUE_ACTIVE.value,
                CollisionPolicy.BLOCKS_LOWER,
                ProviderAuthority.LOCAL_CAN_WRITE,
                RoutineShadowState.NONE,
                null,
                null,
                false,
                false
        );
    }

    private static List<CalendarOccurrence> projectRoutineOccurrences(
            List<ScheduleBlock> blocks,
            Map<RoutineShadowKey, RoutineShadowOverride> shadowOverrides,
            Instant start,
            Instant end,
            ZoneId timezone
    ) {
        LocalDate startDate = start.atZone(timezone).toLocalDate();
        LocalDate endDate = end.minusNanos(1).atZone(timezone).toLocalDate();
        int rangeDays = (int) (endDate.toEpochDay() - startDate.toEpochDay()) + 1;
        List<CalendarOccurrence> occurrences = new ArrayList<>(Math.max(0, rangeDays * blocks.size()));
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            for (ScheduleBlock block : blocks) {
                if (block.getDayOfWeek() != date.getDayOfWeek()) {
                    continue;
                }
                Instant occurrenceStart = date.atTime(block.getStartTime()).atZone(timezone).toInstant();
                LocalDate endLocalDate = block.getEndTime().isAfter(block.getStartTime())
                        ? date
                        : date.plusDays(1);
                Instant occurrenceEnd = endLocalDate.atTime(block.getEndTime()).atZone(timezone).toInstant();
                if (!occurrenceStart.isBefore(end) || !occurrenceEnd.isAfter(start)) {
                    continue;
                }
                RoutineShadowOverride shadowOverride = shadowOverrides.get(new RoutineShadowKey(block.getId(), date));
                occurrences.add(fromRoutine(block, date, occurrenceStart, occurrenceEnd, shadowOverride));
            }
        }
        return occurrences;
    }

    private Map<RoutineShadowKey, RoutineShadowOverride> findShadowOverrides(
            UUID userId,
            List<ScheduleBlock> scheduleBlocks,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (scheduleBlocks.isEmpty()) {
            return Map.of();
        }
        List<UUID> scheduleBlockIds = scheduleBlocks.stream()
                .map(ScheduleBlock::getId)
                .toList();
        return routineShadowOverrideRepository
                .findByUserIdAndScheduleBlockIdInAndShadowDateBetweenAndResolvedAtIsNull(
                        userId,
                        scheduleBlockIds,
                        startDate,
                        endDate
                )
                .stream()
                .collect(Collectors.toMap(
                        override -> new RoutineShadowKey(override.getScheduleBlockId(), override.getShadowDate()),
                        Function.identity(),
                        (existing, replacement) -> replacement
                ));
    }

    private static CalendarOccurrence fromRoutine(
            ScheduleBlock block,
            LocalDate date,
            Instant startAt,
            Instant endAt,
            RoutineShadowOverride shadowOverride
    ) {
        RoutineShadowState shadowState = shadowOverride == null
                ? blockDefaultShadowState(block)
                : shadowOverride.getShadowState();
        boolean protectedWindow = block.isProtectedWindow() || shadowState == RoutineShadowState.SANCTUARY_BLOCKED;
        CollisionPolicy collisionPolicy = protectedWindow
                ? CollisionPolicy.SANCTUARY_PROTECTED
                : block.getShadowPolicy() == RoutineShadowPolicy.NEVER_SHADOW
                ? CollisionPolicy.BLOCKS_LOWER
                : CollisionPolicy.CAN_BE_SHADOWED;
        return new CalendarOccurrence(
                "routine:" + block.getId() + ":" + date,
                CalendarEntityType.ROUTINE_BLOCK,
                block.getId().toString(),
                block.getId().toString(),
                startAt,
                endAt,
                block.getActivity(),
                block.getCategory().name(),
                block.getSourceType().name(),
                PlannerSyncState.LOCAL_ONLY,
                "SINGLE",
                PriorityTier.ROUTINE_TEMPLATE.value,
                collisionPolicy,
                ProviderAuthority.LOCAL_ROUTINE_ONLY,
                shadowState,
                shadowOverride == null ? null : shadowOverride.getShadowingEntityType(),
                shadowOverride == null ? null : shadowOverride.getShadowingEntityId(),
                protectedWindow,
                true
        );
    }

    private static RoutineShadowState blockDefaultShadowState(ScheduleBlock block) {
        return block.isProtectedWindow()
                ? RoutineShadowState.SANCTUARY_BLOCKED
                : RoutineShadowState.NONE;
    }

    private static Set<DayOfWeek> dayOfWeeksBetween(LocalDate startDate, LocalDate endDate) {
        Set<DayOfWeek> dayOfWeeks = new HashSet<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            dayOfWeeks.add(date.getDayOfWeek());
            if (dayOfWeeks.size() == DayOfWeek.values().length) {
                break;
            }
        }
        return dayOfWeeks;
    }

    private static Set<DayOfWeek> lookupDayOfWeeksBetween(
            LocalDate startDate,
            LocalDate endDate,
            Duration rangeDuration
    ) {
        if (!rangeDuration.minus(Duration.ofHours(24)).isNegative()) {
            return Set.of(DayOfWeek.values());
        }
        return dayOfWeeksBetween(startDate.minusDays(1), endDate.plusDays(1));
    }

    private static void validateRange(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start와 end가 필요합니다.");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end는 start 이후여야 합니다.");
        }
        long days = Duration.between(start, end).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("캘린더 조회 범위는 최대 " + MAX_RANGE_DAYS + "일입니다.");
        }
    }

    private static ZoneId resolveTimezone(String requestedTimezone, String userTimezone) {
        String value = requestedTimezone == null || requestedTimezone.isBlank() ? userTimezone : requestedTimezone;
        if (value == null || value.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone 값이 올바르지 않습니다.");
        }
    }

    public enum CalendarView {
        DAY,
        WEEK,
        MONTH,
        AGENDA;

        public static CalendarView from(String value) {
            return CalendarView.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private record RoutineShadowKey(
            UUID scheduleBlockId,
            LocalDate shadowDate
    ) {
    }

    public enum CalendarEntityType {
        EVENT,
        TASK,
        ROUTINE_BLOCK
    }

    public enum CollisionPolicy {
        BLOCKS_LOWER,
        CAN_BE_SHADOWED,
        SANCTUARY_PROTECTED
    }

    public enum ProviderAuthority {
        REMOTE_FIXED,
        LOCAL_CAN_WRITE,
        LOCAL_ROUTINE_ONLY
    }

    public enum PriorityTier {
        GOOGLE_EVENT_FIXED(100),
        TASK_DUE_ACTIVE(70),
        ROUTINE_TEMPLATE(30);

        private final int value;

        PriorityTier(int value) {
            this.value = value;
        }
    }

    public record CalendarRangeResponse(
            Instant start,
            Instant end,
            CalendarView view,
            String timezone,
            List<CalendarOccurrence> occurrences,
            QueryInstrumentation instrumentation
    ) {
    }

    public record CalendarOccurrence(
            String occurrenceId,
            CalendarEntityType entityType,
            String entityId,
            String seriesId,
            Instant startAt,
            Instant endAt,
            String title,
            String category,
            String sourceType,
            PlannerSyncState syncState,
            String recurrenceInstanceType,
            int priorityTier,
            CollisionPolicy collisionPolicy,
            ProviderAuthority providerAuthority,
            RoutineShadowState shadowState,
            ShadowingEntityType shadowingEntityType,
            UUID shadowingEntityId,
            boolean protectedWindow,
            boolean synthetic
    ) {
    }

    public record QueryInstrumentation(
            int repositoryGroupCount,
            List<String> repositoryGroups,
            int occurrenceCount,
            int rangeDays
    ) {
    }
}
