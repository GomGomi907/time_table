package com.timetable.operator.sync.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.sync.domain.SyncConflict;
import com.timetable.operator.sync.domain.SyncConflictResolution;
import com.timetable.operator.sync.domain.SyncConflictStatus;
import com.timetable.operator.sync.domain.SyncDirection;
import com.timetable.operator.sync.domain.SyncExecutionStatus;
import com.timetable.operator.sync.domain.SyncLogEntry;
import com.timetable.operator.sync.domain.SyncResolvePolicy;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import com.timetable.operator.sync.domain.SyncTriggerSource;
import com.timetable.operator.sync.infrastructure.SyncConflictRepository;
import com.timetable.operator.sync.infrastructure.SyncLogEntryRepository;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncOrchestrationService {

    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncLogEntryRepository syncLogEntryRepository;
    private final SyncConflictRepository syncConflictRepository;
    private final AppProperties appProperties;

    @Value("${app.sync.polling.enabled:true}")
    private boolean pollingEnabled;

    @Transactional
    public ManualSyncResponse requestManualSync(SyncTargetSystem targetSystem, ManualSyncRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        SyncRequestOptions options = normalizeRequest(request);
        return executeScaffoldedSync(user.getId(), targetSystem, SyncTriggerSource.MANUAL, options);
    }

    @Transactional(readOnly = true)
    public SyncStatusSnapshot getCurrentUserStatus() {
        AppUser user = currentUserProvider.getCurrentUser();
        Optional<CalendarConnection> googleConnection =
                calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google");

        SyncStatusResponse response = new SyncStatusResponse(
                toTargetStatus(user.getId(), googleConnection, SyncTargetSystem.GOOGLE_CALENDAR),
                toTargetStatus(user.getId(), googleConnection, SyncTargetSystem.GOOGLE_TASKS)
        );

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("webhookTarget", SyncTargetSystem.GOOGLE_CALENDAR.wireValue());
        meta.put("pollingTarget", SyncTargetSystem.GOOGLE_TASKS.wireValue());
        meta.put("pollingEnabled", pollingEnabled);
        meta.put("pendingConflictCount", syncConflictRepository.countByUserIdAndStatus(
                user.getId(),
                SyncConflictStatus.PENDING
        ));
        return new SyncStatusSnapshot(response, Map.copyOf(meta));
    }

    @Transactional
    public SyncConflictResponse resolveConflict(UUID conflictId, ResolveConflictRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        SyncConflict conflict = syncConflictRepository.findByIdAndUserId(conflictId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 sync conflict를 찾을 수 없습니다."));

        if (conflict.getStatus() != SyncConflictStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 sync conflict입니다.");
        }

        conflict.setStatus(SyncConflictStatus.RESOLVED);
        conflict.setResolution(SyncConflictResolution.from(request.resolution()));
        conflict.setResolvedAt(Instant.now());
        return SyncConflictResponse.from(syncConflictRepository.save(conflict));
    }

    @Transactional
    public WebhookReceiptResponse receiveGoogleCalendarWebhook(WebhookReceiptRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        SyncRequestOptions options = defaultOptions();
        ManualSyncResponse run = executeScaffoldedSync(
                user.getId(),
                SyncTargetSystem.GOOGLE_CALENDAR,
                SyncTriggerSource.WEBHOOK,
                options,
                request.channelId(),
                request.resourceState()
        );

        SyncConflict conflict = null;
        if (request.resourceState() != null && !request.resourceState().isBlank()) {
            conflict = new SyncConflict();
            conflict.setUserId(user.getId());
            conflict.setSyncLogId(UUID.fromString(run.syncRunId()));
            conflict.setProvider("google");
            conflict.setTargetSystem(SyncTargetSystem.GOOGLE_CALENDAR);
            conflict.setSummary("Google Calendar 변경 알림이 수신되었습니다.");
            conflict.setDetails("watch/webhook 기반 수신은 구현되어 있지만 실제 inbound diff 적용은 후속 작업입니다.");
            conflict.setExternalRef(blankToNull(request.resourceId()));
            conflict.setStatus(SyncConflictStatus.PENDING);
            conflict.setPayload("""
                    {"channelId":"%s","resourceId":"%s","resourceState":"%s"}
                    """.formatted(
                    blankToEmpty(request.channelId()),
                    blankToEmpty(request.resourceId()),
                    blankToEmpty(request.resourceState())
            ));
            conflict = syncConflictRepository.save(conflict);
        }

        return new WebhookReceiptResponse(
                run.syncRunId(),
                conflict == null ? null : conflict.getId().toString(),
                request.resourceState(),
                run.status(),
                run.detail()
        );
    }

    @Transactional
    public void triggerPollingTasksSync(UUID userId) {
        executeScaffoldedSync(userId, SyncTargetSystem.GOOGLE_TASKS, SyncTriggerSource.POLLING, defaultOptions());
    }

    private ManualSyncResponse executeScaffoldedSync(
            UUID userId,
            SyncTargetSystem targetSystem,
            SyncTriggerSource triggerSource,
            SyncRequestOptions options
    ) {
        return executeScaffoldedSync(userId, targetSystem, triggerSource, options, null, null);
    }

    private ManualSyncResponse executeScaffoldedSync(
            UUID userId,
            SyncTargetSystem targetSystem,
            SyncTriggerSource triggerSource,
            SyncRequestOptions options,
            String webhookChannelId,
            String webhookResourceState
    ) {
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(userId, "google")
                .orElseThrow(() -> new IllegalStateException("Google 연동이 필요합니다."));

        SyncLogEntry syncLogEntry = new SyncLogEntry();
        syncLogEntry.setUserId(userId);
        syncLogEntry.setSyncType(targetSystem.wireValue() + ":" + triggerSource.wireValue());
        syncLogEntry.setProvider("google");
        syncLogEntry.setTargetSystem(targetSystem);
        syncLogEntry.setDirection(options.direction());
        syncLogEntry.setTriggerSource(triggerSource);
        syncLogEntry.setResolvePolicy(options.resolvePolicy());
        syncLogEntry.setStatus(SyncExecutionStatus.RUNNING);
        syncLogEntry.setAffectedCount(0);
        syncLogEntry.setRangeStart(options.rangeStart());
        syncLogEntry.setRangeEnd(options.rangeEnd());
        syncLogEntry.setWebhookChannelId(blankToNull(webhookChannelId));
        syncLogEntry.setWebhookResourceState(blankToNull(webhookResourceState));
        syncLogEntry.setStartedAt(Instant.now());
        syncLogEntry.setDetail(detailFor(targetSystem, triggerSource));
        syncLogEntry = syncLogEntryRepository.save(syncLogEntry);

        syncLogEntry.setStatus(SyncExecutionStatus.SUCCESS);
        syncLogEntry.setFinishedAt(Instant.now());
        syncLogEntry = syncLogEntryRepository.save(syncLogEntry);

        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        connection.setLastSuccessfulSyncAt(syncLogEntry.getFinishedAt());
        connection.setLastSyncError(null);
        calendarConnectionRepository.save(connection);

        return ManualSyncResponse.from(syncLogEntry);
    }

    private SyncStatusTarget toTargetStatus(
            UUID userId,
            Optional<CalendarConnection> googleConnection,
            SyncTargetSystem targetSystem
    ) {
        Optional<SyncLogEntry> latestRun = syncLogEntryRepository
                .findTopByUserIdAndTargetSystemOrderByCreatedAtDesc(userId, targetSystem);

        if (latestRun.isPresent()) {
            return SyncStatusTarget.from(latestRun.get());
        }

        if (googleConnection.isEmpty()) {
            return new SyncStatusTarget(
                    null,
                    "not_connected",
                    0,
                    SyncDirection.INBOUND.wireValue(),
                    null,
                    "Google connection not configured."
            );
        }

        CalendarConnection connection = googleConnection.get();
        return new SyncStatusTarget(
                connection.getLastSuccessfulSyncAt(),
                "never_synced",
                0,
                SyncDirection.INBOUND.wireValue(),
                null,
                "Inbound sync scaffold is ready, but no run has been recorded yet."
        );
    }

    private SyncRequestOptions normalizeRequest(ManualSyncRequest request) {
        if (request == null) {
            return defaultOptions();
        }

        Instant now = Instant.now();
        Instant rangeStart = request.rangeStart() == null
                ? now.minus(appProperties.calendar().syncPastDays(), ChronoUnit.DAYS)
                : request.rangeStart();
        Instant rangeEnd = request.rangeEnd() == null
                ? now.plus(appProperties.calendar().syncFutureDays(), ChronoUnit.DAYS)
                : request.rangeEnd();
        if (rangeEnd.isBefore(rangeStart)) {
            throw new IllegalArgumentException("rangeEnd는 rangeStart 이후여야 합니다.");
        }

        return new SyncRequestOptions(
                SyncDirection.from(request.mode()),
                SyncResolvePolicy.from(request.resolvePolicy()),
                rangeStart,
                rangeEnd
        );
    }

    private SyncRequestOptions defaultOptions() {
        Instant now = Instant.now();
        return new SyncRequestOptions(
                SyncDirection.INBOUND,
                SyncResolvePolicy.PROPOSAL_FIRST,
                now.minus(appProperties.calendar().syncPastDays(), ChronoUnit.DAYS),
                now.plus(appProperties.calendar().syncFutureDays(), ChronoUnit.DAYS)
        );
    }

    private String detailFor(SyncTargetSystem targetSystem, SyncTriggerSource triggerSource) {
        return switch (triggerSource) {
            case MANUAL -> targetSystem.wireValue() + " inbound sync request recorded. Provider adapter wiring is scaffold-only.";
            case WEBHOOK -> "Google Calendar webhook notification recorded and ready for later diff processing.";
            case POLLING -> "Google Tasks polling scaffold recorded a background sync pass.";
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record SyncRequestOptions(
            SyncDirection direction,
            SyncResolvePolicy resolvePolicy,
            Instant rangeStart,
            Instant rangeEnd
    ) {
    }

    public record ManualSyncRequest(
            String mode,
            Instant rangeStart,
            Instant rangeEnd,
            String resolvePolicy
    ) {
    }

    public record ResolveConflictRequest(
            @NotBlank String resolution
    ) {
    }

    public record WebhookReceiptRequest(
            String channelId,
            String resourceId,
            String resourceState
    ) {
    }

    public record ManualSyncResponse(
            String syncRunId,
            String targetSystem,
            String status,
            String mode,
            String triggerSource,
            Instant rangeStart,
            Instant rangeEnd,
            int affectedCount,
            String detail,
            Instant startedAt,
            Instant finishedAt
    ) {
        static ManualSyncResponse from(SyncLogEntry syncLogEntry) {
            return new ManualSyncResponse(
                    syncLogEntry.getId().toString(),
                    syncLogEntry.getTargetSystem().wireValue(),
                    syncLogEntry.getStatus().wireValue(),
                    syncLogEntry.getDirection().wireValue(),
                    syncLogEntry.getTriggerSource().wireValue(),
                    syncLogEntry.getRangeStart(),
                    syncLogEntry.getRangeEnd(),
                    syncLogEntry.getAffectedCount(),
                    syncLogEntry.getDetail(),
                    syncLogEntry.getStartedAt(),
                    syncLogEntry.getFinishedAt()
            );
        }
    }

    public record SyncStatusTarget(
            Instant lastSyncedAt,
            String status,
            int affectedCount,
            String mode,
            String triggerSource,
            String detail
    ) {
        static SyncStatusTarget from(SyncLogEntry syncLogEntry) {
            return new SyncStatusTarget(
                    syncLogEntry.getFinishedAt(),
                    syncLogEntry.getStatus().wireValue(),
                    syncLogEntry.getAffectedCount(),
                    syncLogEntry.getDirection().wireValue(),
                    syncLogEntry.getTriggerSource().wireValue(),
                    syncLogEntry.getDetail()
            );
        }
    }

    public record SyncStatusResponse(
            SyncStatusTarget googleCalendar,
            SyncStatusTarget googleTasks
    ) {
    }

    public record SyncStatusSnapshot(
            SyncStatusResponse data,
            Map<String, Object> meta
    ) {
    }

    public record SyncConflictResponse(
            String id,
            String targetSystem,
            String summary,
            String details,
            String status,
            String resolution,
            Instant resolvedAt,
            String syncRunId
    ) {
        static SyncConflictResponse from(SyncConflict conflict) {
            return new SyncConflictResponse(
                    conflict.getId().toString(),
                    conflict.getTargetSystem().wireValue(),
                    conflict.getSummary(),
                    conflict.getDetails(),
                    conflict.getStatus().wireValue(),
                    conflict.getResolution() == null ? null : conflict.getResolution().wireValue(),
                    conflict.getResolvedAt(),
                    conflict.getSyncLogId() == null ? null : conflict.getSyncLogId().toString()
            );
        }
    }

    public record WebhookReceiptResponse(
            String syncRunId,
            String conflictId,
            String resourceState,
            String status,
            String detail
    ) {
    }
}
