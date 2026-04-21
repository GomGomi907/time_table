package com.timetable.operator.intervention.infrastructure;

import com.timetable.operator.intervention.domain.Intervention;
import com.timetable.operator.intervention.domain.InterventionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterventionRepository extends JpaRepository<Intervention, UUID> {
    List<Intervention> findByUserIdAndStatus(UUID userId, InterventionStatus status);
    Optional<Intervention> findByIdAndUserId(UUID id, UUID userId);
}
