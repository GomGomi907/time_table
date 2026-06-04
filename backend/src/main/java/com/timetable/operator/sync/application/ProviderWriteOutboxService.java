package com.timetable.operator.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.domain.PlannerSyncState;
import com.timetable.operator.events.domain.Event;
import com.timetable.operator.sync.domain.ProviderWriteOperation;
import com.timetable.operator.sync.domain.ProviderWriteOutbox;
import com.timetable.operator.sync.domain.ProviderWriteState;
import com.timetable.operator.sync.domain.SyncMapping;
import com.timetable.operator.sync.domain.SyncMappingLocalType;
import com.timetable.operator.sync.domain.SyncProvider;
import com.timetable.operator.sync.infrastructure.ProviderWriteOutboxRepository;
import com.timetable.operator.sync.infrastructure.SyncMappingRepository;
import com.timetable.operator.tasks.domain.Task;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderWriteOutboxService {

    private static final String GOOGLE_PROVIDER = "google";
    private static final String CALENDAR_EXTERNAL_PREFIX = "google_calendar:";
    private static final String TASK_EXTERNAL_PREFIX = "google_tasks:";
    private static final String RECONNECT_REQUIRED_ERROR_CODE = "reconnect_required";
    private static final String RECONNECT_REQUIRED_ERROR_MESSAGE =
            "Google OAuth token does not include the required write scope.";
    private static final List<ProviderWriteState> COALESCIBLE_STATES = List.of(
            ProviderWriteState.DIRTY_PENDING_WRITE,
            ProviderWriteState.WRITE_FAILED_RETRYABLE,
            ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT
    );

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final ProviderWriteOutboxRepository providerWriteOutboxRepository;
    private final ObjectMapper objectMapper;

    public EnqueueResult enqueueEventWrite(Event event, ProviderWriteOperation requestedOperation) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(event.getUserId(), GOOGLE_PROVIDER);
        Optional<SyncMapping> mapping = findMapping(
                event.getUserId(),
                SyncMappingLocalType.EVENT,
                event.getId(),
                SyncProvider.GOOGLE_CALENDAR,
                stripPrefix(event.getExternalSourceId(), CALENDAR_EXTERNAL_PREFIX)
        );
        ProviderWriteOperation operation = operationFor(requestedOperation, mapping, event.getExternalSourceId());
        boolean allowNewOutbox = operation != ProviderWriteOperation.DELETE
                || mapping.isPresent()
                || !isBlank(event.getExternalSourceId());
        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        if (connection.isEmpty()) {
            boolean queued = coalesceOrSave(
                    event.getUserId(),
                    SyncMappingLocalType.EVENT,
                    event.getId(),
                    mapping.map(SyncMapping::getId).orElse(null),
                    SyncProvider.GOOGLE_CALENDAR,
                    operation,
                    ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT,
                    allowNewOutbox,
                    snapshotEvent(event)
            );
            return queued ? EnqueueResult.NO_CONNECTION : EnqueueResult.NOOP;
        }

        EnqueueResult result = eventWriteResult(connection.get());
        boolean queued = coalesceOrSave(
                event.getUserId(),
                SyncMappingLocalType.EVENT,
                event.getId(),
                mapping.map(SyncMapping::getId).orElse(null),
                SyncProvider.GOOGLE_CALENDAR,
                operation,
                providerWriteState(result),
                allowNewOutbox,
                snapshotEvent(event)
        );
        return queued ? result : EnqueueResult.NOOP;
    }

    public EnqueueResult enqueueTaskWrite(Task task, ProviderWriteOperation requestedOperation) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(task.getUserId(), GOOGLE_PROVIDER);
        Optional<SyncMapping> mapping = findMapping(
                task.getUserId(),
                SyncMappingLocalType.TASK,
                task.getId(),
                SyncProvider.GOOGLE_TASKS,
                stripPrefix(task.getExternalSourceId(), TASK_EXTERNAL_PREFIX)
        );
        ProviderWriteOperation operation = operationFor(requestedOperation, mapping, task.getExternalSourceId());
        boolean allowNewOutbox = operation != ProviderWriteOperation.DELETE
                || mapping.isPresent()
                || !isBlank(task.getExternalSourceId());
        task.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        if (connection.isEmpty()) {
            boolean queued = coalesceOrSave(
                    task.getUserId(),
                    SyncMappingLocalType.TASK,
                    task.getId(),
                    mapping.map(SyncMapping::getId).orElse(null),
                    SyncProvider.GOOGLE_TASKS,
                    operation,
                    ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT,
                    allowNewOutbox,
                    snapshotTask(task)
            );
            return queued ? EnqueueResult.NO_CONNECTION : EnqueueResult.NOOP;
        }

        EnqueueResult result = taskWriteResult(connection.get());
        boolean queued = coalesceOrSave(
                task.getUserId(),
                SyncMappingLocalType.TASK,
                task.getId(),
                mapping.map(SyncMapping::getId).orElse(null),
                SyncProvider.GOOGLE_TASKS,
                operation,
                providerWriteState(result),
                allowNewOutbox,
                snapshotTask(task)
        );
        return queued ? result : EnqueueResult.NOOP;
    }

    private EnqueueResult eventWriteResult(CalendarConnection connection) {
        return connection.isCalendarWriteEnabled()
                ? EnqueueResult.ENQUEUED
                : EnqueueResult.WRITE_SCOPE_REQUIRED;
    }

    private EnqueueResult taskWriteResult(CalendarConnection connection) {
        return connection.isTasksWriteEnabled()
                ? EnqueueResult.ENQUEUED
                : EnqueueResult.WRITE_SCOPE_REQUIRED;
    }

    private ProviderWriteState providerWriteState(EnqueueResult result) {
        return result == EnqueueResult.ENQUEUED
                ? ProviderWriteState.DIRTY_PENDING_WRITE
                : ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT;
    }

    private boolean coalesceOrSave(
            UUID userId,
            SyncMappingLocalType localType,
            UUID localId,
            UUID mappingId,
            SyncProvider provider,
            ProviderWriteOperation requestedOperation,
            ProviderWriteState state,
            boolean allowNewOutbox,
            String payloadSnapshot
    ) {
        Optional<ProviderWriteOutbox> existing = providerWriteOutboxRepository
                .findFirstByUserIdAndLocalTypeAndLocalIdAndProviderAndStateInOrderByCreatedAtAsc(
                        userId,
                        localType,
                        localId,
                        provider,
                        COALESCIBLE_STATES
                );

        if (existing.isEmpty()) {
            if (!allowNewOutbox) {
                return false;
            }
            providerWriteOutboxRepository.save(outbox(
                    userId,
                    localType,
                    localId,
                    mappingId,
                    provider,
                    requestedOperation,
                    state,
                    payloadSnapshot
            ));
            return true;
        }

        ProviderWriteOutbox current = existing.get();
        ProviderWriteOperation mergedOperation = mergeOperation(current.getOperation(), requestedOperation);
        if (mergedOperation == null) {
            providerWriteOutboxRepository.delete(current);
            return false;
        }

        current.setMappingId(mappingId == null ? current.getMappingId() : mappingId);
        current.setOperation(mergedOperation);
        current.setState(state);
        current.setPayloadSnapshot(payloadSnapshot);
        current.setLastErrorCode(needsReconnect(state) ? RECONNECT_REQUIRED_ERROR_CODE : null);
        current.setLastErrorMessage(needsReconnect(state) ? RECONNECT_REQUIRED_ERROR_MESSAGE : null);
        current.setNextRetryAt(null);
        current.setInFlightAt(null);
        current.setAppliedAt(null);
        providerWriteOutboxRepository.save(current);
        return true;
    }

    private ProviderWriteOperation mergeOperation(
            ProviderWriteOperation existingOperation,
            ProviderWriteOperation requestedOperation
    ) {
        if (existingOperation == ProviderWriteOperation.CREATE && requestedOperation == ProviderWriteOperation.DELETE) {
            return null;
        }
        if (existingOperation == ProviderWriteOperation.CREATE) {
            return ProviderWriteOperation.CREATE;
        }
        if (existingOperation == ProviderWriteOperation.DELETE) {
            return ProviderWriteOperation.DELETE;
        }
        if (requestedOperation == ProviderWriteOperation.DELETE) {
            return ProviderWriteOperation.DELETE;
        }
        return requestedOperation;
    }

    private Optional<SyncMapping> findMapping(
            UUID userId,
            SyncMappingLocalType localType,
            UUID localId,
            SyncProvider provider,
            String providerExternalId
    ) {
        Optional<SyncMapping> byLocal = syncMappingRepository
                .findByUserIdAndLocalTypeAndLocalIdAndProvider(userId, localType, localId, provider);
        if (byLocal.isPresent() || isBlank(providerExternalId)) {
            return byLocal;
        }
        return syncMappingRepository.findByUserIdAndProviderAndExternalId(userId, provider, providerExternalId);
    }

    private ProviderWriteOperation operationFor(
            ProviderWriteOperation requestedOperation,
            Optional<SyncMapping> mapping,
            String externalSourceId
    ) {
        if (requestedOperation == ProviderWriteOperation.DELETE) {
            return ProviderWriteOperation.DELETE;
        }
        return mapping.isPresent() || !isBlank(externalSourceId)
                ? ProviderWriteOperation.UPDATE
                : ProviderWriteOperation.CREATE;
    }

    private ProviderWriteOutbox outbox(
            UUID userId,
            SyncMappingLocalType localType,
            UUID localId,
            UUID mappingId,
            SyncProvider provider,
            ProviderWriteOperation operation,
            ProviderWriteState state,
            String payloadSnapshot
    ) {
        ProviderWriteOutbox outbox = new ProviderWriteOutbox();
        outbox.setUserId(userId);
        outbox.setLocalType(localType);
        outbox.setLocalId(localId);
        outbox.setMappingId(mappingId);
        outbox.setProvider(provider);
        outbox.setOperation(operation);
        outbox.setState(state);
        outbox.setPayloadSnapshot(payloadSnapshot);
        outbox.setAttemptCount(0);
        if (needsReconnect(state)) {
            outbox.setLastErrorCode(RECONNECT_REQUIRED_ERROR_CODE);
            outbox.setLastErrorMessage(RECONNECT_REQUIRED_ERROR_MESSAGE);
        }
        return outbox;
    }

    private boolean needsReconnect(ProviderWriteState state) {
        return state == ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT;
    }

    private String snapshotEvent(Event event) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", event.getId());
        snapshot.put("title", event.getTitle());
        snapshot.put("description", event.getDescription());
        snapshot.put("startAt", event.getStartAt());
        snapshot.put("endAt", event.getEndAt());
        snapshot.put("status", event.getStatus());
        snapshot.put("externalSourceId", event.getExternalSourceId());
        snapshot.put("snapshotAt", Instant.now());
        return writeJson(snapshot);
    }

    private String snapshotTask(Task task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", task.getId());
        snapshot.put("title", task.getTitle());
        snapshot.put("description", task.getDescription());
        snapshot.put("dueDate", task.getDueDate());
        snapshot.put("status", task.getStatus());
        snapshot.put("externalSourceId", task.getExternalSourceId());
        snapshot.put("snapshotAt", Instant.now());
        return writeJson(snapshot);
    }

    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String stripPrefix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        return value.substring(prefix.length());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum EnqueueResult {
        ENQUEUED,
        WRITE_SCOPE_REQUIRED,
        NO_CONNECTION,
        NOOP
    }
}
