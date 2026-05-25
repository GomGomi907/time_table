package com.timetable.operator.sync.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.api.UserActionRequiredException;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.sync.domain.SyncConflict;
import com.timetable.operator.sync.domain.SyncConflictResolution;
import com.timetable.operator.sync.domain.SyncConflictStatus;
import com.timetable.operator.sync.domain.SyncDirection;
import com.timetable.operator.sync.domain.SyncExecutionStatus;
import com.timetable.operator.sync.domain.SyncLogEntry;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncResolvePolicy;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import com.timetable.operator.sync.domain.SyncTriggerSource;
import com.timetable.operator.sync.infrastructure.SyncConflictRepository;
import com.timetable.operator.sync.infrastructure.SyncLogEntryRepository;
import com.timetable.operator.sync.infrastructure.ProviderWriteOutboxRepository;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyncOrchestrationService {

    private static final String READ_ONLY_ADAPTER_DETAIL =
            "Google Calendar and Tasks inbound reads are enabled; reconnect with write scopes to enable provider writes.";
    private static final String READ_WRITE_ADAPTER_DETAIL =
            "Google Calendar and Tasks inbound reads plus durable provider write outbox are enabled.";

    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncLogEntryRepository syncLogEntryRepository;
    private final SyncConflictRepository syncConflictRepository;
    private final ProviderWriteOutboxRepository providerWriteOutboxRepository;
    private final AppProperties appProperties;
    private final GoogleInboundSyncClient googleInboundSyncClient;
    private final ProviderWriteProcessor providerWriteProcessor;

    @Value("${app.sync.polling.enabled:true}")
    private boolean pollingEnabled;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ManualSyncResponse requestManualSync(SyncTargetSystem targetSystem, ManualSyncRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        SyncRequestOptions options = normalizeRequest(request);
        return executeInboundSync(user.getId(), targetSystem, SyncTriggerSource.MANUAL, options);
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
        boolean calendarWriteEnabled = googleConnection.map(CalendarConnection::isCalendarWriteEnabled).orElse(false);
        boolean tasksWriteEnabled = googleConnection.map(CalendarConnection::isTasksWriteEnabled).orElse(false);
        boolean externalWriteEnabled = calendarWriteEnabled || tasksWriteEnabled;
        meta.put("adapterMode", externalWriteEnabled ? "read_write" : "read_only");
        meta.put("externalReadEnabled", true);
        meta.put("externalWriteEnabled", externalWriteEnabled);
        meta.put("calendarWriteEnabled", calendarWriteEnabled);
        meta.put("tasksWriteEnabled", tasksWriteEnabled);
        meta.put("capabilityStatus", googleConnection.map(this::capabilityStatus).orElse("not_connected"));
        meta.put("adapterDetail", externalWriteEnabled ? READ_WRITE_ADAPTER_DETAIL : READ_ONLY_ADAPTER_DETAIL);
        meta.put("pendingConflictCount", syncConflictRepository.countByUserIdAndStatus(
                user.getId(),
                SyncConflictStatus.PENDING
        ));
        long providerWritePendingCount = providerWriteOutboxRepository.countByUserIdAndStateIn(
                user.getId(),
                java.util.List.of(ProviderWriteState.DIRTY_PENDING_WRITE, ProviderWriteState.WRITE_IN_FLIGHT)
        );
        long providerWriteRetryableFailureCount = providerWriteOutboxRepository.countByUserIdAndStateIn(
                user.getId(),
                java.util.List.of(ProviderWriteState.WRITE_FAILED_RETRYABLE)
        );
        long providerWriteReconnectRequiredCount = providerWriteOutboxRepository.countByUserIdAndStateIn(
                user.getId(),
                java.util.List.of(ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT)
        );
        long providerWriteConflictCount = providerWriteOutboxRepository.countByUserIdAndStateIn(
                user.getId(),
                java.util.List.of(ProviderWriteState.CONFLICT_PENDING)
        );
        meta.put("providerWritePendingCount", providerWritePendingCount);
        meta.put("providerWriteRetryableFailureCount", providerWriteRetryableFailureCount);
        meta.put("providerWriteReconnectRequiredCount", providerWriteReconnectRequiredCount);
        meta.put("providerWriteConflictCount", providerWriteConflictCount);
        meta.put("pendingProviderWriteCount",
                providerWritePendingCount
                        + providerWriteRetryableFailureCount
                        + providerWriteReconnectRequiredCount
                        + providerWriteConflictCount);
        return new SyncStatusSnapshot(response, Map.copyOf(meta));
    }

    @Transactional(readOnly = true)
    public List<SyncLogResponse> getCurrentUserLogs() {
        AppUser user = currentUserProvider.getCurrentUser();
        return syncLogEntryRepository.findTop20ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(SyncLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SyncConflictResponse> getCurrentUserPendingConflicts() {
        AppUser user = currentUserProvider.getCurrentUser();
        return syncConflictRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                        user.getId(),
                        SyncConflictStatus.PENDING
                ).stream()
                .map(SyncConflictResponse::from)
                .toList();
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
        ManualSyncResponse run = executeInboundSync(
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
            conflict.setDetails("Google Calendar webhook notification triggered an inbound read; review local changes if this notification overlaps manual edits.");
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
        executeInboundSync(userId, SyncTargetSystem.GOOGLE_TASKS, SyncTriggerSource.POLLING, defaultOptions());
    }

    private ManualSyncResponse executeInboundSync(
            UUID userId,
            SyncTargetSystem targetSystem,
            SyncTriggerSource triggerSource,
            SyncRequestOptions options
    ) {
        return executeInboundSync(userId, targetSystem, triggerSource, options, null, null);
    }

    private ManualSyncResponse executeInboundSync(
            UUID userId,
            SyncTargetSystem targetSystem,
            SyncTriggerSource triggerSource,
            SyncRequestOptions options,
            String webhookChannelId,
            String webhookResourceState
    ) {
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(userId, "google")
                .orElseThrow(() -> new UserActionRequiredException("Google 연동이 필요합니다."));

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

        try {
            SyncRunResult result = options.direction() == SyncDirection.OUTBOUND
                    ? executeOutboundSync(userId, targetSystem)
                    : executeInboundProviderSync(connection, targetSystem, options);

            syncLogEntry.setStatus(SyncExecutionStatus.SUCCESS);
            syncLogEntry.setAffectedCount(result.affectedCount());
            syncLogEntry.setDetail(result.detail());
            syncLogEntry.setFinishedAt(Instant.now());
            syncLogEntry = syncLogEntryRepository.save(syncLogEntry);

            connection.setStatus(CalendarConnectionStatus.CONNECTED);
            connection.setLastSuccessfulSyncAt(syncLogEntry.getFinishedAt());
            connection.setLastSyncError(null);
            calendarConnectionRepository.save(connection);
        } catch (RuntimeException exception) {
            syncLogEntry.setStatus(SyncExecutionStatus.FAILED);
            syncLogEntry.setDetail(exception.getMessage());
            syncLogEntry.setFinishedAt(Instant.now());
            syncLogEntry = syncLogEntryRepository.save(syncLogEntry);

            connection.setStatus(CalendarConnectionStatus.DEGRADED);
            connection.setLastSyncError(exception.getMessage());
            calendarConnectionRepository.save(connection);
            throw exception;
        }

        return ManualSyncResponse.from(syncLogEntry);
    }

    private SyncRunResult executeInboundProviderSync(
            CalendarConnection connection,
            SyncTargetSystem targetSystem,
            SyncRequestOptions options
    ) {
        GoogleInboundSyncClient.InboundSyncResult result = switch (targetSystem) {
            case GOOGLE_CALENDAR -> googleInboundSyncClient.importCalendar(
                    connection,
                    options.rangeStart(),
                    options.rangeEnd()
            );
            case GOOGLE_TASKS -> googleInboundSyncClient.importTasks(
                    connection,
                    options.rangeStart(),
                    options.rangeEnd()
            );
        };
        return new SyncRunResult(result.affectedCount(), result.detail());
    }

    private SyncRunResult executeOutboundSync(UUID userId, SyncTargetSystem targetSystem) {
        ProviderWriteProcessor.WriteFlushResult result = providerWriteProcessor.flushPendingWrites(userId, targetSystem);
        return new SyncRunResult(result.affectedCount(), result.detail());
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
        String capabilityStatus = capabilityStatus(connection);
        return new SyncStatusTarget(
                connection.getLastSuccessfulSyncAt(),
                "read_only_token".equals(capabilityStatus) ? "reconnect_required" : "never_synced",
                0,
                SyncDirection.INBOUND.wireValue(),
                null,
                "read_only_token".equals(capabilityStatus)
                        ? "Google reconnect is required before provider writes."
                        : "Google inbound read is enabled, but no run has been recorded yet."
        );
    }

    private String capabilityStatus(CalendarConnection connection) {
        if (connection.getCapabilityStatus() != null && !connection.getCapabilityStatus().isBlank()) {
            return connection.getCapabilityStatus();
        }
        if (connection.isCalendarWriteEnabled() || connection.isTasksWriteEnabled()) {
            return "write_enabled";
        }
        return connection.getStatus() == CalendarConnectionStatus.CONNECTED ? "read_only_token" : "degraded";
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
            case MANUAL -> targetSystem.wireValue() + " sync started.";
            case WEBHOOK -> "Google Calendar webhook notification triggered an inbound read.";
            case POLLING -> "Google Tasks polling triggered an inbound read.";
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

    private record SyncRunResult(int affectedCount, String detail) {
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

    public record SyncLogResponse(
            String id,
            String targetSystem,
            String status,
            String mode,
            String triggerSource,
            String resolvePolicy,
            int affectedCount,
            String detail,
            Instant rangeStart,
            Instant rangeEnd,
            Instant startedAt,
            Instant finishedAt
    ) {
        static SyncLogResponse from(SyncLogEntry syncLogEntry) {
            return new SyncLogResponse(
                    syncLogEntry.getId().toString(),
                    syncLogEntry.getTargetSystem().wireValue(),
                    syncLogEntry.getStatus().wireValue(),
                    syncLogEntry.getDirection().wireValue(),
                    syncLogEntry.getTriggerSource().wireValue(),
                    syncLogEntry.getResolvePolicy().wireValue(),
                    syncLogEntry.getAffectedCount(),
                    syncLogEntry.getDetail(),
                    syncLogEntry.getRangeStart(),
                    syncLogEntry.getRangeEnd(),
                    syncLogEntry.getStartedAt(),
                    syncLogEntry.getFinishedAt()
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
