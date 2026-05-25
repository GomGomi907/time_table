package com.timetable.operator.agent.api;

import com.timetable.operator.agent.application.RescheduleSuggestionService;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import jakarta.validation.Valid;
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
    public ResponseEntity<ApiEnvelope<RescheduleSuggestionService.RescheduleSuggestionResponse>> requestReschedule(
            @Valid @RequestBody RescheduleSuggestionService.ManualRescheduleRequest request
    ) {
        return ApiResponses.ok(rescheduleSuggestionService.createManualSuggestion(request));
    }

    @GetMapping("/api/agent/suggestions")
    public ResponseEntity<ApiEnvelope<List<RescheduleSuggestionService.RescheduleSuggestionResponse>>> getSuggestions() {
        return ApiResponses.ok(rescheduleSuggestionService.getCurrentUserSuggestions());
    }

    @GetMapping("/api/agent/suggestions/{suggestionId}")
    public ResponseEntity<ApiEnvelope<RescheduleSuggestionService.RescheduleSuggestionResponse>> getSuggestion(
            @PathVariable UUID suggestionId
    ) {
        return ApiResponses.ok(rescheduleSuggestionService.getCurrentUserSuggestion(suggestionId));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/apply")
    public ResponseEntity<ApiEnvelope<RescheduleSuggestionService.RescheduleSuggestionResponse>> applySuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) RescheduleSuggestionService.SuggestionDecisionRequest request
    ) {
        return ApiResponses.ok(rescheduleSuggestionService.applySuggestion(suggestionId, request));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/reject")
    public ResponseEntity<ApiEnvelope<RescheduleSuggestionService.RescheduleSuggestionResponse>> rejectSuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) RescheduleSuggestionService.SuggestionDecisionRequest request
    ) {
        return ApiResponses.ok(rescheduleSuggestionService.rejectSuggestion(suggestionId, request));
    }

    @PostMapping("/api/agent/suggestions/{suggestionId}/revert")
    public ResponseEntity<ApiEnvelope<RescheduleSuggestionService.RescheduleSuggestionResponse>> revertSuggestion(
            @PathVariable UUID suggestionId,
            @RequestBody(required = false) RescheduleSuggestionService.SuggestionDecisionRequest request
    ) {
        String reason = request == null ? null : request.reason();
        return ApiResponses.ok(rescheduleSuggestionService.revertSuggestion(suggestionId, reason));
    }
}
