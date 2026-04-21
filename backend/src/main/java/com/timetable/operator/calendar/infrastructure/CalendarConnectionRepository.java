package com.timetable.operator.calendar.infrastructure;

import com.timetable.operator.calendar.domain.CalendarConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {

    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, String provider);
}
