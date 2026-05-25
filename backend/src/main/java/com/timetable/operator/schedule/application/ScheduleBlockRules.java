package com.timetable.operator.schedule.application;

import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduleBlockRules {

    public static final LocalTime OVERNIGHT_DISPLAY_CUTOFF = LocalTime.of(5, 0);
    public static final int MIN_BLOCK_DURATION_MINUTES = 15;
    private static final int DAY_MINUTES = 24 * 60;
    private static final int WEEK_MINUTES = 7 * DAY_MINUTES;
    private static final List<DayOfWeek> DAY_ORDER = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    );

    private final ScheduleBlockRepository scheduleBlockRepository;

    public void validateForUser(UUID userId, ScheduleBlock candidate) {
        validateShape(candidate);
        List<ScheduleBlock> existingBlocks = scheduleBlockRepository.findByUserId(userId).stream()
                .filter(existing -> !sameBlock(existing, candidate))
                .toList();
        assertNoOverlap(candidate, existingBlocks);
    }

    public void validateBatch(UUID userId, List<ScheduleBlock> candidates, boolean replacingExisting) {
        List<ScheduleBlock> combined = new ArrayList<>();
        if (!replacingExisting) {
            combined.addAll(scheduleBlockRepository.findByUserId(userId));
        }
        combined.addAll(candidates);

        combined.forEach(this::validateShape);
        assertNoOverlap(combined);
    }

    public ShiftedScheduleBlock shift(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, long shiftMinutes) {
        int durationMinutes = durationMinutes(startTime, endTime);
        int shiftedStart = (int) Math.floorMod((long) toAbsoluteWeekMinute(dayOfWeek, startTime) + shiftMinutes, WEEK_MINUTES);
        int shiftedEnd = shiftedStart + durationMinutes;
        return new ShiftedScheduleBlock(
                toAnchorDay(shiftedStart),
                toClockTime(shiftedStart),
                toClockTime(shiftedEnd)
        );
    }

    private void validateShape(ScheduleBlock block) {
        if (block.getDayOfWeek() == null || block.getStartTime() == null || block.getEndTime() == null) {
            throw new IllegalArgumentException("요일과 시작/종료 시각은 모두 필요합니다.");
        }
        if (block.getActivity() == null || block.getActivity().isBlank()) {
            throw new IllegalArgumentException("활동 이름은 비어 있을 수 없습니다.");
        }
        if (block.getStartTime().equals(block.getEndTime())) {
            throw new IllegalArgumentException("일정 블록 시작 시각과 종료 시각은 같을 수 없습니다.");
        }
        if (durationMinutes(block.getStartTime(), block.getEndTime()) < MIN_BLOCK_DURATION_MINUTES) {
            throw new IllegalArgumentException("일정 블록은 최소 %d분 이상이어야 합니다.".formatted(MIN_BLOCK_DURATION_MINUTES));
        }
    }

    private void assertNoOverlap(List<ScheduleBlock> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            ScheduleBlock candidate = blocks.get(index);
            assertNoOverlap(candidate, blocks.subList(index + 1, blocks.size()));
        }
    }

    private void assertNoOverlap(ScheduleBlock candidate, List<ScheduleBlock> others) {
        BlockInterval candidateInterval = toInterval(candidate);
        for (ScheduleBlock other : others) {
            BlockInterval otherInterval = toInterval(other);
            if (overlaps(candidateInterval, otherInterval)) {
                throw new IllegalArgumentException(
                        "일정 블록이 기존 블록과 겹칩니다: %s (%s-%s)".formatted(
                                other.getActivity(),
                                other.getStartTime(),
                                other.getEndTime()
                        )
                );
            }
        }
    }

    private boolean overlaps(BlockInterval left, BlockInterval right) {
        return intersects(left.startMinute(), left.endMinute(), right.startMinute(), right.endMinute())
                || intersects(left.startMinute(), left.endMinute(), right.startMinute() + WEEK_MINUTES, right.endMinute() + WEEK_MINUTES)
                || intersects(left.startMinute() + WEEK_MINUTES, left.endMinute() + WEEK_MINUTES, right.startMinute(), right.endMinute());
    }

    private boolean intersects(int leftStart, int leftEnd, int rightStart, int rightEnd) {
        return leftStart < rightEnd && rightStart < leftEnd;
    }

    private BlockInterval toInterval(ScheduleBlock block) {
        int startMinute = toAbsoluteWeekMinute(block.getDayOfWeek(), block.getStartTime());
        int durationMinutes = durationMinutes(block.getStartTime(), block.getEndTime());
        return new BlockInterval(block.getId(), startMinute, startMinute + durationMinutes);
    }

    private int durationMinutes(LocalTime startTime, LocalTime endTime) {
        int diff = toDisplayMinute(endTime) - toDisplayMinute(startTime);
        return diff > 0 ? diff : diff + DAY_MINUTES;
    }

    private int toAbsoluteWeekMinute(DayOfWeek dayOfWeek, LocalTime time) {
        return dayIndex(dayOfWeek) * DAY_MINUTES + toDisplayMinute(time);
    }

    private int toDisplayMinute(LocalTime time) {
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        if (time.isBefore(OVERNIGHT_DISPLAY_CUTOFF)) {
            return minuteOfDay + DAY_MINUTES;
        }
        return minuteOfDay;
    }

    private DayOfWeek toAnchorDay(int absoluteMinute) {
        int dayIndex = Math.floorDiv(absoluteMinute, DAY_MINUTES);
        int minuteOfDay = Math.floorMod(absoluteMinute, DAY_MINUTES);
        if (minuteOfDay < OVERNIGHT_DISPLAY_CUTOFF.getHour() * 60) {
            dayIndex -= 1;
        }
        return DAY_ORDER.get(Math.floorMod(dayIndex, DAY_ORDER.size()));
    }

    private LocalTime toClockTime(int absoluteMinute) {
        int minuteOfDay = Math.floorMod(absoluteMinute, DAY_MINUTES);
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    private int dayIndex(DayOfWeek dayOfWeek) {
        return DAY_ORDER.indexOf(dayOfWeek);
    }

    private boolean sameBlock(ScheduleBlock left, ScheduleBlock right) {
        return left.getId() != null && left.getId().equals(right.getId());
    }

    private record BlockInterval(
            UUID id,
            int startMinute,
            int endMinute
    ) {
    }

    public record ShiftedScheduleBlock(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {
    }
}
