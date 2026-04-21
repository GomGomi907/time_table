package com.timetable.operator.priority.infrastructure;

import com.timetable.operator.priority.domain.PriorityAdjustmentProposal;
import com.timetable.operator.priority.domain.PriorityProposalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriorityAdjustmentProposalRepository extends JpaRepository<PriorityAdjustmentProposal, UUID> {

    List<PriorityAdjustmentProposal> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<PriorityAdjustmentProposal> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, PriorityProposalStatus status);

    Optional<PriorityAdjustmentProposal> findByIdAndUserId(UUID id, UUID userId);
}
