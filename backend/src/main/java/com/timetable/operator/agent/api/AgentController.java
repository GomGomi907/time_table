package com.timetable.operator.agent.api;

import com.timetable.operator.agent.application.RescheduleSuggestionService;
import com.timetable.operator.agent.domain.StructuredAiCommandBatch;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentController {

    private final RescheduleSuggestionService rescheduleSuggestionService;

    @PostMapping("/api/agent/reschedule")
    public ResponseEntity<ApiEnvelope<AgentSuggestionResponse>> requestReschedule(
            @Valid @RequestBody ManualRescheduleRequest request
    ) {
        return ApiResponses.ok(AgentSuggestionResponse.from(
                rescheduleSuggestionService.createManualSuggestion(request.toServiceRequest())
        ));
    }

    @GetMapping("/api/agent/suggestions")
    public ResponseEntity<ApiEnvelope<List<AgentSuggestionResponse>>> getSuggestions() {
        return ApiResponses.ok(rescheduleSuggestionService.getCurrentUserSuggestions().stream()
                .map(AgentSuggestionResponse::from)
                .toList());
    }

    @GetMapping("/api/agent/suggestions/{suggestionId}")
    public ResponseEntity<ApiEnvelope<AgentSuggestionResponse>> getSuggestion(
            @PathVariable UUID suggestionId
    ) {
        return ApiResponses.ok(AgentSuggestionResponse.from(rescheduleSuggestionService.getCurrentUserSuggestion(suggestionId)));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/apply")
    public ResponseEntity<ApiEnvelope<AgentSuggestionResponse>> applySuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) SuggestionDecisionRequest request
    ) {
        return ApiResponses.ok(AgentSuggestionResponse.from(
                rescheduleSuggestionService.applySuggestion(suggestionId, toServiceDecision(request))
        ));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/reject")
    public ResponseEntity<ApiEnvelope<AgentSuggestionResponse>> rejectSuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) SuggestionDecisionRequest request
    ) {
        return ApiResponses.ok(AgentSuggestionResponse.from(
                rescheduleSuggestionService.rejectSuggestion(suggestionId, toServiceDecision(request))
        ));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/revert")
    public ResponseEntity<ApiEnvelope<AgentSuggestionResponse>> revertSuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) SuggestionDecisionRequest request
    ) {
        String reason = request == null ? null : request.reason();
        return ApiResponses.ok(AgentSuggestionResponse.from(
                rescheduleSuggestionService.revertSuggestion(suggestionId, reason)
        ));
    }

    private static RescheduleSuggestionService.SuggestionDecisionRequest toServiceDecision(
            SuggestionDecisionRequest request
    ) {
        return request == null ? null : new RescheduleSuggestionService.SuggestionDecisionRequest(request.reason());
    }

    public record ManualRescheduleRequest(
            String triggerType,
            Instant targetRangeStart,
            Instant targetRangeEnd,
            @NotBlank String reason
    ) {
        private RescheduleSuggestionService.ManualRescheduleRequest toServiceRequest() {
            return new RescheduleSuggestionService.ManualRescheduleRequest(
                    triggerType,
                    targetRangeStart,
                    targetRangeEnd,
                    reason
            );
        }
    }

    public record SuggestionDecisionRequest(String reason) {
    }

    public record AgentSuggestionResponse(
            String id,
            String triggerType,
            String status,
            String statusLabel,
            String statusDetail,
            String summary,
            String reason,
            String explanation,
            StructuredAiCommandBatch commandBatch,
            List<AgentSuggestionPreviewItemResponse> previewItems,
            int executableCommandCount,
            boolean executable,
            AgentSuggestionExecutionSummaryResponse executionSummary,
            Instant createdAt,
            Instant appliedAt,
            Instant rejectedAt,
            Instant revertedAt
    ) {
        private static AgentSuggestionResponse from(RescheduleSuggestionService.RescheduleSuggestionResponse response) {
            return new AgentSuggestionResponse(
                    response.id(),
                    response.triggerType(),
                    response.status(),
                    response.statusLabel(),
                    response.statusDetail(),
                    response.summary(),
                    response.reason(),
                    response.explanation(),
                    response.commandBatch(),
                    response.previewItems().stream()
                            .map(AgentSuggestionPreviewItemResponse::from)
                            .toList(),
                    response.executableCommandCount(),
                    response.executable(),
                    AgentSuggestionExecutionSummaryResponse.from(response.executionSummary()),
                    response.createdAt(),
                    response.appliedAt(),
                    response.rejectedAt(),
                    response.revertedAt()
            );
        }
    }

    public record AgentSuggestionPreviewItemResponse(
            String actionType,
            String targetType,
            String targetId,
            String title,
            String detail,
            String reason,
            boolean executable
    ) {
        private static AgentSuggestionPreviewItemResponse from(
                RescheduleSuggestionService.SuggestionPreviewItemResponse response
        ) {
            return new AgentSuggestionPreviewItemResponse(
                    response.actionType(),
                    response.targetType(),
                    response.targetId(),
                    response.title(),
                    response.detail(),
                    response.reason(),
                    response.executable()
            );
        }
    }

    public record AgentSuggestionExecutionSummaryResponse(
            int totalCount,
            int appliedCount,
            int noOpCount,
            String detail
    ) {
        private static AgentSuggestionExecutionSummaryResponse from(
                RescheduleSuggestionService.SuggestionExecutionSummaryResponse response
        ) {
            if (response == null) {
                return null;
            }
            return new AgentSuggestionExecutionSummaryResponse(
                    response.totalCount(),
                    response.appliedCount(),
                    response.noOpCount(),
                    response.detail()
            );
        }
    }
}
