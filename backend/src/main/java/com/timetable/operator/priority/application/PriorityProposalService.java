package com.timetable.operator.priority.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.priority.domain.PriorityAdjustmentProposal;
import com.timetable.operator.priority.domain.PriorityProposalStatus;
import com.timetable.operator.priority.domain.PriorityProposalTargetType;
import com.timetable.operator.priority.infrastructure.PriorityAdjustmentProposalRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriorityProposalService {

    private final PriorityAdjustmentProposalRepository priorityAdjustmentProposalRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public List<PriorityProposalResponse> getCurrentUserProposals() {
        AppUser user = currentUserProvider.getCurrentUser();
        return priorityAdjustmentProposalRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(PriorityProposalResponse::from)
                .toList();
    }

    @Transactional
    public PriorityProposalResponse accept(UUID proposalId) {
        return decide(proposalId, PriorityProposalStatus.ACCEPTED);
    }

    @Transactional
    public PriorityProposalResponse reject(UUID proposalId) {
        return decide(proposalId, PriorityProposalStatus.REJECTED);
    }

    @Transactional
    public PriorityProposalResponse createProposal(CreatePriorityProposalCommand command) {
        AppUser user = currentUserProvider.getCurrentUser();
        validatePriority(command.currentPriority());
        validatePriority(command.proposedPriority());

        PriorityAdjustmentProposal proposal = new PriorityAdjustmentProposal();
        proposal.setUserId(user.getId());
        proposal.setTargetType(command.targetType());
        proposal.setTargetId(command.targetId());
        proposal.setCurrentPriority(command.currentPriority());
        proposal.setProposedPriority(command.proposedPriority());
        proposal.setReason(command.reason());
        proposal.setStatus(PriorityProposalStatus.PENDING);
        return PriorityProposalResponse.from(priorityAdjustmentProposalRepository.save(proposal));
    }

    private PriorityProposalResponse decide(UUID proposalId, PriorityProposalStatus nextStatus) {
        AppUser user = currentUserProvider.getCurrentUser();
        PriorityAdjustmentProposal proposal = priorityAdjustmentProposalRepository.findByIdAndUserId(proposalId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 priority proposal을 찾을 수 없습니다."));

        if (proposal.getStatus() != PriorityProposalStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 priority proposal입니다.");
        }

        proposal.setStatus(nextStatus);
        proposal.setDecidedAt(Instant.now());
        return PriorityProposalResponse.from(priorityAdjustmentProposalRepository.save(proposal));
    }

    private void validatePriority(short priority) {
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("priority는 1부터 5 사이여야 합니다.");
        }
    }

    public record CreatePriorityProposalCommand(
            PriorityProposalTargetType targetType,
            UUID targetId,
            short currentPriority,
            short proposedPriority,
            String reason
    ) {
    }

    public record PriorityProposalResponse(
            String id,
            String targetType,
            String targetId,
            short currentPriority,
            short proposedPriority,
            String reason,
            String status,
            Instant createdAt,
            Instant decidedAt
    ) {
        static PriorityProposalResponse from(PriorityAdjustmentProposal proposal) {
            return new PriorityProposalResponse(
                    proposal.getId().toString(),
                    proposal.getTargetType().wireValue(),
                    proposal.getTargetId().toString(),
                    proposal.getCurrentPriority(),
                    proposal.getProposedPriority(),
                    proposal.getReason(),
                    proposal.getStatus().wireValue(),
                    proposal.getCreatedAt(),
                    proposal.getDecidedAt()
            );
        }
    }
}
