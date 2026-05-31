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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderWriteOutboxService {

    private static final String GOOGLE_PROVIDER = "google";
    private static final String CALENDAR_EXTERNAL_PREFIX = "google_calendar:";
    private static final String TASK_EXTERNAL_PREFIX = "google_tasks:";
    private static final List<ProviderWriteState> COALESCIBLE_STATES = List.of(
            ProviderWriteState.DIRTY_PENDING_WRITE,
            ProviderWriteState.WRITE_FAILED_RETRYABLE,
            ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT
    );

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final ProviderWriteOutboxRepository providerWriteOutboxRepository;
    private final ObjectMapper objectMapper;

    public void enqueueEventWrite(Event event, ProviderWriteOperation requestedOperation) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(event.getUserId(), GOOGLE_PROVIDER);
        if (connection.isEmpty()) {
            return;
        }

        Optional<SyncMapping> mapping = findMapping(
                SyncMappingLocalType.EVENT,
                event.getId(),
                SyncProvider.GOOGLE_CALENDAR,
                stripPrefix(event.getExternalSourceId(), CALENDAR_EXTERNAL_PREFIX)
        );
        ProviderWriteOperation operation = operationFor(requestedOperation, mapping, event.getExternalSourceId());
        if (operation == ProviderWriteOperation.DELETE && mapping.isEmpty() && isBlank(event.getExternalSourceId())) {
            return;
        }

        event.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        coalesceOrSave(
                event.getUserId(),
                SyncMappingLocalType.EVENT,
                event.getId(),
                mapping.map(SyncMapping::getId).orElse(null),
                SyncProvider.GOOGLE_CALENDAR,
                operation,
                connection.get().isCalendarWriteEnabled()
                        ? ProviderWriteState.DIRTY_PENDING_WRITE
                        : ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT,
                snapshotEvent(event)
        );
    }

    public void enqueueTaskWrite(Task task, ProviderWriteOperation requestedOperation) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(task.getUserId(), GOOGLE_PROVIDER);
        if (connection.isEmpty()) {
            return;
        }

        Optional<SyncMapping> mapping = findMapping(
                SyncMappingLocalType.TASK,
                task.getId(),
                SyncProvider.GOOGLE_TASKS,
                stripPrefix(task.getExternalSourceId(), TASK_EXTERNAL_PREFIX)
        );
        ProviderWriteOperation operation = operationFor(requestedOperation, mapping, task.getExternalSourceId());
        if (operation == ProviderWriteOperation.DELETE && mapping.isEmpty() && isBlank(task.getExternalSourceId())) {
            return;
        }

        task.setSyncState(PlannerSyncState.DIRTY_PENDING_WRITE);
        coalesceOrSave(
                task.getUserId(),
                SyncMappingLocalType.TASK,
                task.getId(),
                mapping.map(SyncMapping::getId).orElse(null),
                SyncProvider.GOOGLE_TASKS,
                operation,
                connection.get().isTasksWriteEnabled()
                        ? ProviderWriteState.DIRTY_PENDING_WRITE
                        : ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT,
                snapshotTask(task)
        );
    }

    private void coalesceOrSave(
            java.util.UUID userId,
            SyncMappingLocalType localType,
            java.util.UUID localId,
            java.util.UUID mappingId,
            SyncProvider provider,
            ProviderWriteOperation requestedOperation,
            ProviderWriteState state,
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
            return;
        }

        ProviderWriteOutbox current = existing.get();
        ProviderWriteOperation mergedOperation = mergeOperation(current.getOperation(), requestedOperation);
        if (mergedOperation == null) {
            providerWriteOutboxRepository.delete(current);
            return;
        }

        current.setMappingId(mappingId == null ? current.getMappingId() : mappingId);
        current.setOperation(mergedOperation);
        current.setState(state);
        current.setPayloadSnapshot(payloadSnapshot);
        current.setLastErrorCode(state == ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT ? "reconnect_required" : null);
        current.setLastErrorMessage(state == ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT
                ? "Google OAuth token does not include the required write scope."
                : null);
        current.setNextRetryAt(null);
        current.setInFlightAt(null);
        current.setAppliedAt(null);
        providerWriteOutboxRepository.save(current);
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
            SyncMappingLocalType localType,
            java.util.UUID localId,
            SyncProvider provider,
            String providerExternalId
    ) {
        Optional<SyncMapping> byLocal = syncMappingRepository.findByLocalTypeAndLocalIdAndProvider(localType, localId, provider);
        if (byLocal.isPresent() || isBlank(providerExternalId)) {
            return byLocal;
        }
        return syncMappingRepository.findByProviderAndExternalId(provider, providerExternalId);
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
            java.util.UUID userId,
            SyncMappingLocalType localType,
            java.util.UUID localId,
            java.util.UUID mappingId,
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
        if (state == ProviderWriteState.WRITE_FAILED_NEEDS_RECONNECT) {
            outbox.setLastErrorCode("reconnect_required");
            outbox.setLastErrorMessage("Google OAuth token does not include the required write scope.");
        }
        return outbox;
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
}
