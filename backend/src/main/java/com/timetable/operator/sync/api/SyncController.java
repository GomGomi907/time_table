package com.timetable.operator.sync.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import com.timetable.operator.sync.application.SyncOrchestrationService;
import com.timetable.operator.sync.domain.SyncTargetSystem;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncOrchestrationService syncOrchestrationService;

    @PostMapping("/google/calendar")
    public ResponseEntity<ApiEnvelope<SyncOrchestrationService.ManualSyncResponse>> requestGoogleCalendarSync(
            @RequestBody(required = false) SyncOrchestrationService.ManualSyncRequest request
    ) {
        return ApiResponses.ok(syncOrchestrationService.requestManualSync(SyncTargetSystem.GOOGLE_CALENDAR, request));
    }

    @PostMapping("/google/tasks")
    public ResponseEntity<ApiEnvelope<SyncOrchestrationService.ManualSyncResponse>> requestGoogleTasksSync(
            @RequestBody(required = false) SyncOrchestrationService.ManualSyncRequest request
    ) {
        return ApiResponses.ok(syncOrchestrationService.requestManualSync(SyncTargetSystem.GOOGLE_TASKS, request));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiEnvelope<SyncOrchestrationService.SyncStatusResponse>> getStatus() {
        SyncOrchestrationService.SyncStatusSnapshot snapshot = syncOrchestrationService.getCurrentUserStatus();
        return ApiResponses.ok(snapshot.data(), snapshot.meta());
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    public ResponseEntity<ApiEnvelope<SyncOrchestrationService.SyncConflictResponse>> resolveConflict(
            @PathVariable UUID conflictId,
            @Valid @RequestBody SyncOrchestrationService.ResolveConflictRequest request
    ) {
        return ApiResponses.ok(syncOrchestrationService.resolveConflict(conflictId, request));
    }

    @PostMapping("/google/calendar/webhook")
    public ResponseEntity<ApiEnvelope<SyncOrchestrationService.WebhookReceiptResponse>> receiveGoogleCalendarWebhook(
            @RequestHeader(value = "X-Goog-Channel-Id", required = false) String channelId,
            @RequestHeader(value = "X-Goog-Resource-Id", required = false) String resourceId,
            @RequestHeader(value = "X-Goog-Resource-State", required = false) String resourceState
    ) {
        return ApiResponses.ok(syncOrchestrationService.receiveGoogleCalendarWebhook(
                new SyncOrchestrationService.WebhookReceiptRequest(channelId, resourceId, resourceState)
        ));
    }
}
