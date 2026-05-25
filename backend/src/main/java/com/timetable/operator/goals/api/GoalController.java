package com.timetable.operator.goals.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.goals.application.GoalService;
import com.timetable.operator.goals.domain.Goal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @GetMapping
    public ApiEnvelope<List<GoalResponse>> listGoals() {
        return ApiEnvelope.ok(goalService.listGoals().stream().map(GoalResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiEnvelope<GoalResponse> getGoal(@PathVariable UUID id) {
        return ApiEnvelope.ok(GoalResponse.from(goalService.getGoal(id)));
    }

    @PostMapping
    public ApiEnvelope<GoalResponse> createGoal(@Validated @RequestBody GoalService.CreateGoalRequest request) {
        return ApiEnvelope.ok(GoalResponse.from(goalService.createGoal(request)));
    }

    @PatchMapping("/{id}")
    public ApiEnvelope<GoalResponse> updateGoal(
            @PathVariable UUID id,
            @Validated @RequestBody GoalService.CreateGoalRequest request
    ) {
        return ApiEnvelope.ok(GoalResponse.from(goalService.updateGoal(id, request)));
    }

    @PutMapping("/{id}")
    public ApiEnvelope<GoalResponse> replaceGoal(
            @PathVariable UUID id,
            @Validated @RequestBody GoalService.CreateGoalRequest request
    ) {
        return ApiEnvelope.ok(GoalResponse.from(goalService.updateGoal(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiEnvelope<Void> deleteGoal(@PathVariable UUID id) {
        goalService.deleteGoal(id);
        return ApiEnvelope.ok(null);
    }

    @PostMapping("/{id}/progress")
    public ApiEnvelope<GoalResponse> updateProgress(
            @PathVariable UUID id,
            @Validated @RequestBody GoalService.ProgressUpdateRequest request
    ) {
        return ApiEnvelope.ok(GoalResponse.from(goalService.updateProgress(id, request)));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GoalResponse(
            String id,
            String parentId,
            String title,
            String description,
            String category,
            String status,
            int progress,
            String goalType,
            String metricUnit,
            BigDecimal targetValue,
            BigDecimal currentValue,
            String progressRule,
            LocalDate startDate,
            LocalDate endDate,
            short priority
    ) {
        public static GoalResponse from(Goal goal) {
            return new GoalResponse(
                    goal.getId().toString(),
                    goal.getParentGoalId() == null ? null : goal.getParentGoalId().toString(),
                    goal.getTitle(),
                    goal.getDescription(),
                    goal.getCategory().name(),
                    goal.getStatus().name(),
                    goal.getProgress(),
                    goal.getGoalType().name(),
                    goal.getMetricUnit(),
                    goal.getTargetValue(),
                    goal.getCurrentValue(),
                    goal.getProgressRule(),
                    goal.getStartDate(),
                    goal.getEndDate(),
                    goal.getPriority()
            );
        }
    }
}
