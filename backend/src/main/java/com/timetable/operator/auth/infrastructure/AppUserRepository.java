package com.timetable.operator.auth.infrastructure;

import com.timetable.operator.auth.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByGoogleSubject(String googleSubject);

    Optional<AppUser> findByEmail(String email);
}
