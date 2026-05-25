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
import java.util.Comparator;
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
        seedDefaultsIfNeeded(user);
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

    private void seedDefaultsIfNeeded(AppUser user) {
        if (goalRepository.existsByUserId(user.getId())) {
            return;
        }

        Goal english = new Goal();
        english.setUserId(user.getId());
        english.setTitle("영어 공부");
        english.setDescription("이번 달 영어 공부 20시간 누적");
        english.setCategory(GoalCategory.GROWTH);
        english.setStatus(GoalStatus.IN_PROGRESS);
        english.setProgress(35);
        english.setGoalType(GoalType.QUANTITATIVE);
        english.setMetricUnit("hours");
        english.setTargetValue(BigDecimal.valueOf(20));
        english.setCurrentValue(BigDecimal.valueOf(7));
        english.setProgressRule("{\"type\":\"sum_task_minutes\"}");
        english.setStartDate(LocalDate.now().withDayOfMonth(1));
        english.setEndDate(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()));
        english.setPriority((short) 1);

        Goal health = new Goal();
        health.setUserId(user.getId());
        health.setTitle("주 3회 운동");
        health.setDescription("운동 루틴을 주간 시간표에 안정적으로 배치");
        health.setCategory(GoalCategory.HEALTH);
        health.setStatus(GoalStatus.IN_PROGRESS);
        health.setProgress(50);
        health.setGoalType(GoalType.HYBRID);
        health.setMetricUnit("sessions");
        health.setTargetValue(BigDecimal.valueOf(3));
        health.setCurrentValue(BigDecimal.valueOf(1.5));
        health.setProgressRule("{\"type\":\"count_completed_events\"}");
        health.setStartDate(LocalDate.now().minusDays(2));
        health.setEndDate(LocalDate.now().plusDays(5));
        health.setPriority((short) 2);

        Goal project = new Goal();
        project.setUserId(user.getId());
        project.setTitle("시간표 서비스 MVP");
        project.setDescription("문서-설계-구현-검증까지 한 주 안에 완료");
        project.setCategory(GoalCategory.CAREER);
        project.setStatus(GoalStatus.IN_PROGRESS);
        project.setProgress(20);
        project.setGoalType(GoalType.DURATION);
        project.setMetricUnit("hours");
        project.setTargetValue(BigDecimal.valueOf(30));
        project.setCurrentValue(BigDecimal.valueOf(6));
        project.setProgressRule("{\"type\":\"manual\"}");
        project.setStartDate(LocalDate.now());
        project.setEndDate(LocalDate.now().plusDays(7));
        project.setPriority((short) 1);

        goalRepository.saveAll(List.of(english, health, project).stream()
                .sorted(Comparator.comparing(Goal::getTitle))
                .toList());
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
