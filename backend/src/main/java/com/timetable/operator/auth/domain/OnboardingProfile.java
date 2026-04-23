package com.timetable.operator.auth.domain;

import com.timetable.operator.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "onboarding_profiles")
public class OnboardingProfile extends AuditableEntity {

    @Column(nullable = false, unique = true)
    private UUID userId;

    private LocalTime wakeTime;

    private LocalTime workStartTime;

    private LocalTime dinnerTime;

    private LocalTime sleepTime;

    private String weekendStyle;

    private Instant bootstrapCompletedAt;

    private Instant onboardingCompletedAt;
}
