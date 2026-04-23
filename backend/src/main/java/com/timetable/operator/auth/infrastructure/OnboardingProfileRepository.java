package com.timetable.operator.auth.infrastructure;

import com.timetable.operator.auth.domain.OnboardingProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProfileRepository extends JpaRepository<OnboardingProfile, UUID> {

    Optional<OnboardingProfile> findByUserId(UUID userId);
}
