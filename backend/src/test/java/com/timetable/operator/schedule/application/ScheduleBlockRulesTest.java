package com.timetable.operator.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.domain.ScheduleCategory;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleBlockRulesTest {

    private final ScheduleBlockRepository scheduleBlockRepository = mock(ScheduleBlockRepository.class);
    private final ScheduleBlockRules scheduleBlockRules = new ScheduleBlockRules(scheduleBlockRepository);

    @Test
    void shiftCrossingPastOvernightCutoffAdvancesAnchorDay() {
        ScheduleBlockRules.ShiftedScheduleBlock shifted = scheduleBlockRules.shift(
                DayOfWeek.MONDAY,
                LocalTime.of(23, 0),
                LocalTime.of(0, 0),
                420
        );

        assertThat(shifted.dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(shifted.startTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(shifted.endTime()).isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    void validateForUserRejectsOverlappingBlocks() {
        UUID userId = UUID.randomUUID();

        ScheduleBlock existing = new ScheduleBlock();
        existing.setId(UUID.randomUUID());
        existing.setUserId(userId);
        existing.setDayOfWeek(DayOfWeek.MONDAY);
        existing.setStartTime(LocalTime.of(9, 0));
        existing.setEndTime(LocalTime.of(10, 0));
        existing.setActivity("기존 블록");
        existing.setCategory(ScheduleCategory.WORK);

        ScheduleBlock candidate = new ScheduleBlock();
        candidate.setUserId(userId);
        candidate.setDayOfWeek(DayOfWeek.MONDAY);
        candidate.setStartTime(LocalTime.of(9, 30));
        candidate.setEndTime(LocalTime.of(10, 30));
        candidate.setActivity("겹치는 블록");
        candidate.setCategory(ScheduleCategory.GROWTH);

        when(scheduleBlockRepository.findByUserId(userId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> scheduleBlockRules.validateForUser(userId, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("겹칩니다");
    }

    @Test
    void validateBatchRejectsExactDuplicateCandidates() {
        UUID userId = UUID.randomUUID();

        ScheduleBlock first = new ScheduleBlock();
        first.setUserId(userId);
        first.setDayOfWeek(DayOfWeek.MONDAY);
        first.setStartTime(LocalTime.of(9, 0));
        first.setEndTime(LocalTime.of(10, 0));
        first.setActivity("중복 블록");
        first.setCategory(ScheduleCategory.WORK);

        ScheduleBlock duplicate = new ScheduleBlock();
        duplicate.setUserId(userId);
        duplicate.setDayOfWeek(DayOfWeek.MONDAY);
        duplicate.setStartTime(LocalTime.of(9, 0));
        duplicate.setEndTime(LocalTime.of(10, 0));
        duplicate.setActivity("중복 블록");
        duplicate.setCategory(ScheduleCategory.WORK);

        when(scheduleBlockRepository.findByUserId(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> scheduleBlockRules.validateBatch(userId, List.of(first, duplicate), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("겹칩니다");
    }

    @Test
    void validateForUserRejectsBlocksShorterThanUiGranularity() {
        UUID userId = UUID.randomUUID();
        ScheduleBlock candidate = new ScheduleBlock();
        candidate.setUserId(userId);
        candidate.setDayOfWeek(DayOfWeek.MONDAY);
        candidate.setStartTime(LocalTime.of(9, 0));
        candidate.setEndTime(LocalTime.of(9, 1));
        candidate.setActivity("너무 짧은 블록");
        candidate.setCategory(ScheduleCategory.WORK);

        when(scheduleBlockRepository.findByUserId(userId)).thenReturn(List.of());

        assertThatThrownBy(() -> scheduleBlockRules.validateForUser(userId, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 15분");
    }
}
