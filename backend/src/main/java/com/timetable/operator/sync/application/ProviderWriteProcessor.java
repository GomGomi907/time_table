package com.timetable.operator.sync.application;

import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.events.domain.EventSourceType;
import com.timetable.operator.events.infrastructure.EventRepository;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.sync.domain.ProviderWriteOutbox;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncExecutionStatus;
import com.timetable.operator.sync.domain.SyncMapping;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncMappingStatus;
import com.timetable.operator.sync.domain.SyncProvider;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import com.timetable.operator.sync.domain.TombstoneState;
import com.timetable.operator.sync.infrastructure.ProviderWriteOutboxRepository;
import com.timetable.operator.sync.infrastructure.SyncMappingRepository;
import com.timetable.operator.tasks.domain.Task;
import com.timetable.operator.tasks.domain.TaskSourceType;
import com.timetable.operator.tasks.infrastructure.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
@RequiredArgsConstructor
public class ProviderWriteProcessor {

    private static final String GOOGLE_PROVIDER = "google";
    private static final String CALENDAR_EXTERNAL_PREFIX = "google_calendar:";
    private static final String TASK_EXTERNAL_PREFIX = "google_tasks:";
    private static final String RECONNECT_REQUIRED_ERROR_CODE = "reconnect_required";
    private static final String RECONNECT_REQUIRED_ERROR_MESSAGE = "Google reconnect with write scope is required.";
    private static final String PROVIDER_WRITES_DISABLED_DETAIL =
            "Provider writes are disabled by APP_SYNC_GOOGLE_WRITE_ENABLED=false; no Google writes attempted.";
    private static final List<ProviderWriteState> READY_STATES = List.of(
            ProviderWriteState.DIRTY_PENDING_WRITE,
            ProviderWriteState.WRITE_FAILED_RETRYABLE,
            ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT
    );

    private final ProviderWriteOutboxRepository providerWriteOutboxRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final GoogleOutboundSyncClient googleOutboundSyncClient;

    @Value("${app.sync.google.write-enabled:true}")
    private boolean googleWriteEnabled;

    @Transactional
    public WriteFlushResult flushPendingWrites(UUID userId, SyncTargetSystem targetSystem) {
        if (!googleWriteEnabled) {
            return new WriteFlushResult(
                    0,
                    0,
                    0,
                    0,
                    SyncExecutionStatus.FAILED,
                    PROVIDER_WRITES_DISABLED_DETAIL
            );
        }

        SyncProvider provider = targetSystem == SyncTargetSystem.GOOGLE_CALENDAR
                ? SyncProvider.GOOGLE_CALENDAR
                : SyncProvider.GOOGLE_TASKS;
        List<ProviderWriteOutbox> pending = providerWriteOutboxRepository
                .findByUserIdAndProviderAndStateInOrderByCreatedAtAsc(userId, provider, READY_STATES);

        int successCount = 0;
        int failureCount = 0;
        int skippedRetryCount = 0;
        for (ProviderWriteOutbox outbox : pending) {
            if (outbox.getNextRetryAt() != null && outbox.getNextRetryAt().isAfter(Instant.now())) {
                skippedRetryCount++;
                continue;
            }
            try {
                processOne(userId, outbox);
                successCount++;
            } catch (RuntimeException exception) {
                failureCount++;
                markFailure(userId, outbox, exception);
            }
        }
        SyncExecutionStatus status = statusFor(successCount, failureCount, skippedRetryCount);
        String detail = skippedRetryCount == 0
                ? "Provider write flush completed: %d applied, %d failed.".formatted(successCount, failureCount)
                : "Provider write flush completed: %d applied, %d failed, %d waiting for retry."
                        .formatted(successCount, failureCount, skippedRetryCount);
        return new WriteFlushResult(
                successCount + failureCount + skippedRetryCount,
                successCount,
                failureCount,
                skippedRetryCount,
                status,
                detail
        );
    }

    private static SyncExecutionStatus statusFor(int successCount, int failureCount, int skippedRetryCount) {
        int unresolvedCount = failureCount + skippedRetryCount;
        if (unresolvedCount == 0) {
            return SyncExecutionStatus.SUCCESS;
        }
        return successCount == 0 ? SyncExecutionStatus.FAILED : SyncExecutionStatus.PARTIAL_FAILURE;
    }

    private void processOne(UUID userId, ProviderWriteOutbox outbox) {
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER)
                .orElseThrow(() -> new IllegalStateException("Google 연동이 필요합니다."));
        ensureWriteCapability(connection, outbox.getProvider());

        outbox.setState(ProviderWriteState.WRITE_IN_FLIGHT);
        outbox.setInFlightAt(Instant.now());
        outbox.setAttemptCount(outbox.getAttemptCount() + 1);
        providerWriteOutboxRepository.save(outbox);

        if (outbox.getLocalType() == SyncMappingLocalType.EVENT) {
            processEvent(connection, outbox);
        } else {
            processTask(connection, outbox);
        }
    }

    private void processEvent(CalendarConnection connection, ProviderWriteOutbox outbox) {
        Event event = eventRepository.findById(outbox.getLocalId())
                .orElseThrow(() -> new IllegalStateException("Provider write 대상 이벤트를 찾을 수 없습니다."));
        Optional<SyncMapping> mapping = findMapping(outbox, SyncProvider.GOOGLE_CALENDAR);
        String providerEventId = mapping.map(SyncMapping::getExternalId)
                .orElseGet(() -> stripPrefix(event.getExternalSourceId(), CALENDAR_EXTERNAL_PREFIX));

        if (outbox.getOperation() == ProviderWriteOperation.DELETE) {
            if (providerEventId != null) {
                googleOutboundSyncClient.deleteCalendarEvent(connection, providerEventId);
            }
            markEventDeleted(event, mapping.orElse(null), outbox);
            return;
        }

        GoogleOutboundSyncClient.ProviderWriteResult result = providerEventId == null
                ? googleOutboundSyncClient.createCalendarEvent(connection, event)
                : googleOutboundSyncClient.updateCalendarEvent(connection, providerEventId, event);
        String externalId = result.providerId() == null ? providerEventId : result.providerId();
        SyncMapping savedMapping = upsertMapping(
                outbox,
                SyncMappingLocalType.EVENT,
                SyncProvider.GOOGLE_CALENDAR,
                externalId,
                result.externalEtag(),
                "{\"calendarId\":\"primary\"}"
        );
        event.setSourceType(EventSourceType.GOOGLE_CALENDAR);
        event.setSyncState(PlannerSyncState.SYNCED);
        event.setExternalSourceId(CALENDAR_EXTERNAL_PREFIX + externalId);
        event.setExternalEtag(result.externalEtag());
        event.setLastSyncedAt(Instant.now());
        eventRepository.save(event);
        markSynced(outbox, savedMapping.getId());
    }

    private void processTask(CalendarConnection connection, ProviderWriteOutbox outbox) {
        Task task = taskRepository.findById(outbox.getLocalId())
                .orElseThrow(() -> new IllegalStateException("Provider write 대상 태스크를 찾을 수 없습니다."));
        Optional<SyncMapping> mapping = findMapping(outbox, SyncProvider.GOOGLE_TASKS);
        TaskExternalRef externalRef = TaskExternalRef.from(mapping.map(SyncMapping::getExternalId)
                .orElseGet(() -> stripPrefix(task.getExternalSourceId(), TASK_EXTERNAL_PREFIX)));

        if (outbox.getOperation() == ProviderWriteOperation.DELETE) {
            if (externalRef.taskId() != null) {
                googleOutboundSyncClient.deleteTask(connection, externalRef.taskListId(), externalRef.taskId());
            }
            markTaskDeleted(task, mapping.orElse(null), outbox);
            return;
        }

        GoogleOutboundSyncClient.ProviderWriteResult result = externalRef.taskId() == null
                ? googleOutboundSyncClient.createTask(connection, externalRef.taskListId(), task)
                : googleOutboundSyncClient.updateTask(connection, externalRef.taskListId(), externalRef.taskId(), task);
        String providerTaskId = result.providerId() == null ? externalRef.taskId() : result.providerId();
        String externalId = externalRef.taskListId() + ":" + providerTaskId;
        SyncMapping savedMapping = upsertMapping(
                outbox,
                SyncMappingLocalType.TASK,
                SyncProvider.GOOGLE_TASKS,
                externalId,
                result.externalEtag(),
                "{\"taskListId\":\"%s\"}".formatted(externalRef.taskListId())
        );
        task.setSourceType(TaskSourceType.GOOGLE_TASKS);
        task.setSyncState(PlannerSyncState.SYNCED);
        task.setExternalSourceId(TASK_EXTERNAL_PREFIX + externalId);
        task.setExternalEtag(result.externalEtag());
        task.setLastSyncedAt(Instant.now());
        taskRepository.save(task);
        markSynced(outbox, savedMapping.getId());
    }

    private SyncMapping upsertMapping(
            ProviderWriteOutbox outbox,
            SyncMappingLocalType localType,
            SyncProvider provider,
            String externalId,
            String externalEtag,
            String metadata
    ) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalStateException("Provider write response did not include an external id.");
        }
        SyncMapping mapping = findMapping(outbox, provider)
                .orElseGet(SyncMapping::new);
        mapping.setUserId(outbox.getUserId());
        mapping.setLocalType(localType);
        mapping.setLocalId(outbox.getLocalId());
        mapping.setProvider(provider);
        mapping.setExternalId(externalId);
        mapping.setExternalEtag(externalEtag);
        mapping.setSyncStatus(SyncMappingStatus.ACTIVE);
        mapping.setTombstoneState(TombstoneState.NONE);
        mapping.setLastSyncedAt(Instant.now());
        mapping.setMetadata(metadata);
        return syncMappingRepository.save(mapping);
    }

    private Optional<SyncMapping> findMapping(ProviderWriteOutbox outbox, SyncProvider provider) {
        if (outbox.getMappingId() != null) {
            Optional<SyncMapping> byId = syncMappingRepository.findById(outbox.getMappingId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return syncMappingRepository.findByLocalTypeAndLocalIdAndProvider(outbox.getLocalType(), outbox.getLocalId(), provider);
    }

    private void markEventDeleted(Event event, SyncMapping mapping, ProviderWriteOutbox outbox) {
        event.setSyncState(PlannerSyncState.DETACHED);
        event.setLastSyncedAt(Instant.now());
        eventRepository.save(event);
        markMappingDeleted(mapping);
        markSynced(outbox, mapping == null ? null : mapping.getId());
    }

    private void markTaskDeleted(Task task, SyncMapping mapping, ProviderWriteOutbox outbox) {
        task.setSyncState(PlannerSyncState.DETACHED);
        task.setLastSyncedAt(Instant.now());
        taskRepository.save(task);
        markMappingDeleted(mapping);
        markSynced(outbox, mapping == null ? null : mapping.getId());
    }

    private void markMappingDeleted(SyncMapping mapping) {
        if (mapping == null) {
            return;
        }
        mapping.setSyncStatus(SyncMappingStatus.DETACHED);
        mapping.setTombstoneState(TombstoneState.LOCAL_DELETED);
        mapping.setLocalDeletedAt(Instant.now());
        mapping.setLastSyncedAt(Instant.now());
        syncMappingRepository.save(mapping);
    }

    private void markSynced(ProviderWriteOutbox outbox, UUID mappingId) {
        outbox.setState(ProviderWriteState.SYNCED);
        outbox.setMappingId(mappingId);
        outbox.setAppliedAt(Instant.now());
        outbox.setLastErrorCode(null);
        outbox.setLastErrorMessage(null);
        outbox.setNextRetryAt(null);
        providerWriteOutboxRepository.save(outbox);
    }

    private void markFailure(UUID userId, ProviderWriteOutbox outbox, RuntimeException exception) {
        boolean authFailure = isAuthFailure(exception);
        ProviderWriteState state = authFailure
                ? ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT
                : ProviderWriteState.WRITE_FAILED_RETRYABLE;
        outbox.setState(state);
        outbox.setLastErrorCode(authFailure ? RECONNECT_REQUIRED_ERROR_CODE : exception.getClass().getSimpleName());
        outbox.setLastErrorMessage(exception.getMessage());
        outbox.setNextRetryAt(state == ProviderWriteState.WRITE_FAILED_RETRYABLE
                ? Instant.now().plusSeconds(Math.min(3_600L, Math.max(60L, outbox.getAttemptCount() * 60L)))
                : null);
        providerWriteOutboxRepository.save(outbox);
        if (state == ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT) {
            calendarConnectionRepository.findByUserIdAndProvider(userId, GOOGLE_PROVIDER).ifPresent(connection -> {
                connection.setStatus(CalendarConnectionStatus.DEGRADED);
                connection.setCapabilityStatus("read_only_token");
                connection.setCapabilityError(exception.getMessage());
                calendarConnectionRepository.save(connection);
            });
        }
    }

    private void ensureWriteCapability(CalendarConnection connection, SyncProvider provider) {
        boolean enabled = provider == SyncProvider.GOOGLE_CALENDAR
                ? connection.isCalendarWriteEnabled()
                : connection.isTasksWriteEnabled();
        if (!enabled) {
            throw new IllegalStateException(RECONNECT_REQUIRED_ERROR_MESSAGE);
        }
    }

    private boolean isAuthFailure(RuntimeException exception) {
        if (exception instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().value() == 401
                    || responseException.getStatusCode().value() == 403;
        }
        return exception instanceof IllegalStateException
                && exception.getMessage() != null
                && exception.getMessage().toLowerCase().contains("reconnect");
    }

    private String stripPrefix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        return value.substring(prefix.length());
    }

    public record WriteFlushResult(
            int affectedCount,
            int successCount,
            int failureCount,
            int skippedRetryCount,
            SyncExecutionStatus status,
            String detail
    ) {
    }

    private record TaskExternalRef(String taskListId, String taskId) {

        static TaskExternalRef from(String externalId) {
            if (externalId == null || externalId.isBlank()) {
                return new TaskExternalRef("@default", null);
            }
            int separator = externalId.indexOf(':');
            if (separator < 0) {
                return new TaskExternalRef("@default", externalId);
            }
            return new TaskExternalRef(externalId.substring(0, separator), externalId.substring(separator + 1));
        }
    }
}
