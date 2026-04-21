package com.timetable.operator.goals.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.timetable.operator.goals.application.GoalService;
import com.timetable.operator.goals.domain.Goal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @GetMapping
    public List<GoalResponse> listGoals() {
        return goalService.listGoals().stream()
                .map(GoalResponse::from)
                .toList();
    }

    @PostMapping
    public GoalResponse createGoal(@Validated @RequestBody GoalService.CreateGoalRequest request) {
        return GoalResponse.from(goalService.createGoal(request));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GoalResponse(
            String id,
            String parentId,
            String title,
            String description,
            String category,
            String status,
            int progress
    ) {
        static GoalResponse from(Goal goal) {
            return new GoalResponse(
                    goal.getId().toString(),
                    goal.getParentGoalId() == null ? null : goal.getParentGoalId().toString(),
                    goal.getTitle(),
                    goal.getDescription(),
                    goal.getCategory().name(),
                    goal.getStatus().name(),
                    goal.getProgress()
            );
        }
    }
}
