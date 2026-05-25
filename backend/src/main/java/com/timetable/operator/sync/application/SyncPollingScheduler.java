package com.timetable.operator.sync.application;

import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.sync.polling.enabled", havingValue = "true", matchIfMissing = true)
public class SyncPollingScheduler {

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncOrchestrationService syncOrchestrationService;

    @Scheduled(
            fixedDelayString = "${app.sync.polling.fixed-delay-ms:900000}",
            initialDelayString = "${app.sync.polling.initial-delay-ms:60000}"
    )
    public void pollGoogleTasksInboundSync() {
        calendarConnectionRepository.findAll().stream()
                .filter(connection -> "google".equalsIgnoreCase(connection.getProvider()))
                .filter(connection -> connection.getStatus() == CalendarConnectionStatus.CONNECTED
                        || connection.getStatus() == CalendarConnectionStatus.DEGRADED)
                .map(connection -> connection.getUserId())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::safelyTriggerPollingSync);
    }

    private void safelyTriggerPollingSync(UUID userId) {
        try {
            syncOrchestrationService.triggerPollingTasksSync(userId);
        } catch (Exception exception) {
            log.warn("Google Tasks polling inbound sync failed for user {}", userId, exception);
        }
    }
}
