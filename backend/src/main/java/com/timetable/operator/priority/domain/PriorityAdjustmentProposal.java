package com.timetable.operator.priority.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "priority_adjustment_proposals")
public class PriorityAdjustmentProposal extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriorityProposalTargetType targetType;

    @Column(nullable = false)
    private UUID targetId;

    @Column(nullable = false)
    private short currentPriority;

    @Column(nullable = false)
    private short proposedPriority;

    @Column(columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriorityProposalStatus status;

    private Instant decidedAt;
}
