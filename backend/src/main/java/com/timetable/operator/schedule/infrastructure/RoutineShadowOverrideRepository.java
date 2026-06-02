package com.timetable.operator.schedule.infrastructure;

import com.timetable.operator.schedule.domain.RoutineShadowOverride;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineShadowOverrideRepository extends JpaRepository<RoutineShadowOverride, UUID> {

    List<RoutineShadowOverride> findByUserIdAndShadowDateBetweenAndResolvedAtIsNull(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<RoutineShadowOverride> findByUserIdAndScheduleBlockIdInAndShadowDateBetweenAndResolvedAtIsNull(
            UUID userId,
            Collection<UUID> scheduleBlockIds,
            LocalDate startDate,
            LocalDate endDate
    );
}
