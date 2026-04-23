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
}
