package com.timetable.operator.intervention.api;

import com.timetable.operator.intervention.application.InterventionService;
import com.timetable.operator.intervention.domain.Intervention;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interventions")
@RequiredArgsConstructor
public class InterventionController {

    private final InterventionService interventionService;

    @GetMapping
    public List<InterventionResponse> getInterventions() {
        return interventionService.getPendingInterventions()
            .stream()
            .map(InterventionResponse::from)
            .toList();
    }

    @PostMapping("/{id}/decide")
    public void decide(@PathVariable UUID id, @RequestBody InterventionService.DecisionRequest request) {
        interventionService.decide(id, request);
    }

    public record InterventionResponse(
        UUID id,
        String triggerType,
        String summary,
        String recommendation,
        String impactAnalysis,
        String status
    ) {
        public static InterventionResponse from(Intervention i) {
            return new InterventionResponse(
                i.getId(),
                i.getTriggerType().name(),
                i.getSummary(),
                i.getRecommendation(),
                i.getImpactAnalysis(),
                i.getStatus().name()
            );
        }
    }
}
