package com.timetable.operator.goals.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "goals")
public class Goal extends AuditableEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID parentGoalId;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status;

    @Column(nullable = false)
    private int progress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalType goalType = GoalType.DURATION;

    private String metricUnit;

    @Column(precision = 12, scale = 2)
    private BigDecimal targetValue;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentValue = BigDecimal.ZERO;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String progressRule = "{\"type\":\"manual\"}";

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private short priority = 3;
}
