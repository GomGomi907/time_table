package com.timetable.operator.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.events.domain.EventStatus;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.schedule.domain.RoutineShadowOverride;
import com.timetable.operator.schedule.domain.RoutineShadowState;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.domain.ScheduleSourceType;
import com.timetable.operator.schedule.domain.ShadowingEntityType;
import com.timetable.operator.schedule.infrastructure.RoutineShadowOverrideRepository;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CalendarRangeServiceTest {
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ScheduleBlockRepository scheduleBlockRepository = mock(ScheduleBlockRepository.class);
    private final RoutineShadowOverrideRepository routineShadowOverrideRepository =
            mock(RoutineShadowOverrideRepository.class);
    private final CalendarRangeService calendarRangeService = new CalendarRangeService(
            currentUserProvider,
            eventRepository,
            taskRepository,
            scheduleBlockRepository,
            routineShadowOverrideRepository
    );

    @Test
    void rangeLoadsOnlyRoutineDaysInsideExclusiveEndBoundaryAndAppliesShadowOverrides() {
        UUID userId = UUID.randomUUID();
        UUID scheduleBlockId = UUID.randomUUID();
        UUID shadowingEntityId = UUID.randomUUID();
        AppUser user = user(userId);
        ScheduleBlock block = scheduleBlock(scheduleBlockId, userId, DayOfWeek.MONDAY);
        RoutineShadowOverride shadowOverride = shadowOverride(
                userId,
                scheduleBlockId,
                LocalDate.parse("2026-06-01"),
                shadowingEntityId
        );
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");

        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(eventRepository.findByUserIdAndStatusNotAndStartAtBeforeAndEndAtAfterOrderByStartAtAsc(
                eq(userId),
                eq(EventStatus.CANCELLED),
                eq(end),
                eq(start)
        )).thenReturn(List.of());
        when(taskRepository.findByUserIdAndStatusInAndDueDateBetweenOrderByDueDateAsc(
                eq(userId),
                any(),
                eq(start),
                eq(end)
        )).thenReturn(List.of());
        when(scheduleBlockRepository.findByUserIdAndDayOfWeekIn(eq(userId), any())).thenReturn(List.of(block));
        when(routineShadowOverrideRepository.findByUserIdAndScheduleBlockIdInAndShadowDateBetweenAndResolvedAtIsNull(
                eq(userId),
                any(),
                eq(LocalDate.parse("2026-06-01")),
                eq(LocalDate.parse("2026-06-01"))
        )).thenReturn(List.of(shadowOverride));

        CalendarRangeService.CalendarRangeResponse response = calendarRangeService.getRange(
                start,
                end,
                CalendarRangeService.CalendarView.DAY,
                "UTC"
        );

        verify(scheduleBlockRepository).findByUserIdAndDayOfWeekIn(
                eq(userId),
                argThat(days -> days.contains(DayOfWeek.SUNDAY)
                        && days.contains(DayOfWeek.MONDAY)
                        && days.contains(DayOfWeek.TUESDAY))
        );
        verify(routineShadowOverrideRepository)
                .findByUserIdAndScheduleBlockIdInAndShadowDateBetweenAndResolvedAtIsNull(
                        eq(userId),
                        argThat(ids -> ids.size() == 1 && ids.contains(scheduleBlockId)),
                        eq(LocalDate.parse("2026-06-01")),
                        eq(LocalDate.parse("2026-06-01"))
                );
        assertThat(response.occurrences()).hasSize(1);
        assertThat(response.occurrences().get(0).shadowState()).isEqualTo(RoutineShadowState.SHADOWED_BY_EVENT);
        assertThat(response.occurrences().get(0).shadowingEntityType()).isEqualTo(ShadowingEntityType.EVENT);
        assertThat(response.occurrences().get(0).shadowingEntityId()).isEqualTo(shadowingEntityId);
        assertThat(response.occurrences().get(0).protectedWindow()).isFalse();
    }

    private static AppUser user(UUID userId) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("calendar@example.com");
        user.setDisplayName("Calendar User");
        user.setProvider("local");
        user.setTimezone("UTC");
        return user;
    }

    private static ScheduleBlock scheduleBlock(UUID blockId, UUID userId, DayOfWeek dayOfWeek) {
        ScheduleBlock block = new ScheduleBlock();
        ReflectionTestUtils.setField(block, "id", blockId);
        block.setUserId(userId);
        block.setDayOfWeek(dayOfWeek);
        block.setStartTime(LocalTime.parse("09:00"));
        block.setEndTime(LocalTime.parse("10:00"));
        block.setActivity("독서");
        block.setCategory(ScheduleCategory.GROWTH);
        block.setSourceType(ScheduleSourceType.MANUAL);
        return block;
    }

    private static RoutineShadowOverride shadowOverride(
            UUID userId,
            UUID scheduleBlockId,
            LocalDate shadowDate,
            UUID shadowingEntityId
    ) {
        RoutineShadowOverride override = new RoutineShadowOverride();
        override.setUserId(userId);
        override.setScheduleBlockId(scheduleBlockId);
        override.setShadowDate(shadowDate);
        override.setShadowState(RoutineShadowState.SHADOWED_BY_EVENT);
        override.setShadowingEntityType(ShadowingEntityType.EVENT);
        override.setShadowingEntityId(shadowingEntityId);
        override.setReason("Google event blocks this routine.");
        return override;
    }
}
