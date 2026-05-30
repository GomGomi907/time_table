package com.timetable.operator.goals.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.goals.domain.Goal;
import com.timetable.operator.goals.domain.GoalCategory;
import com.timetable.operator.goals.domain.GoalStatus;
import com.timetable.operator.goals.domain.GoalType;
import com.timetable.operator.goals.infrastructure.GoalRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository goalRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional
    public List<Goal> listGoals() {
        AppUser user = currentUserProvider.getCurrentUser();
        return goalRepository.findByUserIdOrderByPriorityAscCreatedAtAsc(user.getId());
    }

    @Transactional(readOnly = true)
    public Goal getGoal(UUID id) {
        AppUser user = currentUserProvider.getCurrentUser();
        return goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 목표를 찾을 수 없습니다."));
    }

    @Transactional
    public Goal createGoal(CreateGoalRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Goal goal = new Goal();
        goal.setUserId(user.getId());
        applyWrite(goal, request);
        return goalRepository.save(goal);
    }

    @Transactional
    public Goal updateGoal(UUID id, CreateGoalRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 목표를 찾을 수 없습니다."));
        applyWrite(goal, request);
        return goalRepository.save(goal);
    }

    @Transactional
    public void deleteGoal(UUID id) {
        AppUser user = currentUserProvider.getCurrentUser();
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 목표를 찾을 수 없습니다."));
        goal.setStatus(GoalStatus.ARCHIVED);
        goalRepository.save(goal);
    }

    @Transactional
    public Goal updateProgress(UUID id, ProgressUpdateRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 목표를 찾을 수 없습니다."));

        BigDecimal current = goal.getCurrentValue() == null ? BigDecimal.ZERO : goal.getCurrentValue();
        BigDecimal delta = request.deltaValue() == null ? BigDecimal.ZERO : request.deltaValue();
        BigDecimal next = current.add(delta);
        goal.setCurrentValue(next);

        if (goal.getTargetValue() != null && goal.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
            int progress = next.multiply(BigDecimal.valueOf(100))
                    .divide(goal.getTargetValue(), java.math.RoundingMode.DOWN)
                    .intValue();
            goal.setProgress(Math.max(0, Math.min(100, progress)));
        }

        if (goal.getProgress() >= 100) {
            goal.setStatus(GoalStatus.COMPLETED);
        }

        return goalRepository.save(goal);
    }

    private void applyWrite(Goal goal, CreateGoalRequest request) {
        goal.setParentGoalId(request.parentId());
        goal.setTitle(request.title().trim());
        goal.setDescription(blankToNull(request.description()));
        goal.setCategory(request.category() == null ? GoalCategory.GROWTH : request.category());
        goal.setStatus(request.status() == null ? GoalStatus.IN_PROGRESS : request.status());
        goal.setProgress(request.progress() == null ? 0 : request.progress());
        goal.setGoalType(request.goalType() == null ? GoalType.DURATION : request.goalType());
        goal.setMetricUnit(blankToNull(request.metricUnit()));
        goal.setTargetValue(request.targetValue());
        goal.setCurrentValue(request.currentValue());
        goal.setProgressRule(blankToNull(request.progressRule()));
        goal.setStartDate(request.startDate());
        goal.setEndDate(request.endDate());
        goal.setPriority(request.priority() == null ? 3 : request.priority());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CreateGoalRequest(
            UUID parentId,
            @NotBlank String title,
            String description,
            GoalCategory category,
            GoalStatus status,
            GoalType goalType,
            String metricUnit,
            BigDecimal targetValue,
            BigDecimal currentValue,
            String progressRule,
            LocalDate startDate,
            LocalDate endDate,
            @Min(1) @Max(5) Short priority,
            @Min(0) @Max(100) Integer progress
    ) {
    }

    public record ProgressUpdateRequest(
            @NotNull BigDecimal deltaValue,
            String reason
    ) {
    }
}
