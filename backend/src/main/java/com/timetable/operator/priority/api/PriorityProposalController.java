package com.timetable.operator.priority.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import com.timetable.operator.priority.application.PriorityProposalService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/priority/proposals")
@RequiredArgsConstructor
public class PriorityProposalController {

    private final PriorityProposalService priorityProposalService;

    @GetMapping
    public ResponseEntity<ApiEnvelope<List<PriorityProposalService.PriorityProposalResponse>>> getProposals() {
        return ApiResponses.ok(priorityProposalService.getCurrentUserProposals());
    }

    @PostMapping("/{proposalId}/accept")
    public ResponseEntity<ApiEnvelope<PriorityProposalService.PriorityProposalResponse>> accept(
            @PathVariable UUID proposalId
    ) {
        return ApiResponses.ok(priorityProposalService.accept(proposalId));
    }

    @PostMapping("/{proposalId}/reject")
    public ResponseEntity<ApiEnvelope<PriorityProposalService.PriorityProposalResponse>> reject(
            @PathVariable UUID proposalId
    ) {
        return ApiResponses.ok(priorityProposalService.reject(proposalId));
    }
}
