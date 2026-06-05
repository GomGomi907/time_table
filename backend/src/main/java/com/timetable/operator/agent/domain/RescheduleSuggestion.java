package com.timetable.operator.agent.domain;

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
@Table(name = "reschedule_suggestions")
public class RescheduleSuggestion extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RescheduleSuggestionTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RescheduleSuggestionStatus status;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String originalRequest;

    @Column(columnDefinition = "text")
    private String decisionReason;

    @Column(columnDefinition = "text")
    private String explanation;

    @Column(nullable = false, columnDefinition = "text")
    private String suggestionPayload;

    @Column(columnDefinition = "text")
    private String executionSnapshot;

    private Instant appliedAt;

    private Instant rejectedAt;

    private Instant revertedAt;

    private Instant expiresAt;
}
