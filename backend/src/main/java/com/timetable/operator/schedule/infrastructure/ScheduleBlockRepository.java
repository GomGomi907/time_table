package com.timetable.operator.schedule.infrastructure;

import com.timetable.operator.schedule.domain.ScheduleBlock;
import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleBlockRepository extends JpaRepository<ScheduleBlock, UUID> {

    boolean existsByUserId(UUID userId);

    long countByUserId(UUID userId);

    List<ScheduleBlock> findByUserId(UUID userId);

    List<ScheduleBlock> findByUserIdAndDayOfWeekIn(UUID userId, Collection<DayOfWeek> dayOfWeeks);

    Optional<ScheduleBlock> findByIdAndUserId(UUID id, UUID userId);

    void deleteByUserId(UUID userId);
}
