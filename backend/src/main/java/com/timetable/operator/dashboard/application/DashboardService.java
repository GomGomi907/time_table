package com.timetable.operator.dashboard.application;

import com.timetable.operator.agent.application.RescheduleSuggestionService;
import com.timetable.operator.focus.application.FocusService;
import com.timetable.operator.goals.application.GoalService;
import com.timetable.operator.goals.domain.Goal;
import com.timetable.operator.schedule.application.ScheduleService;
import com.timetable.operator.sync.application.SyncOrchestrationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ScheduleService scheduleService;
    private final GoalService goalService;
    private final FocusService focusService;
    private final SyncOrchestrationService syncOrchestrationService;
    private final RescheduleSuggestionService rescheduleSuggestionService;

    @Transactional
    public DashboardSummaryResponse getSummary() {
        ScheduleService.WeekScheduleResponse week = scheduleService.getWeeklySchedule();
        List<DashboardGoalResponse> goals = goalService.listGoals().stream()
                .map(DashboardGoalResponse::from)
                .toList();
        FocusService.FocusCurrentView focus = focusService.getCurrentFocus();
        SyncOrchestrationService.SyncStatusSnapshot syncSnapshot = syncOrchestrationService.getCurrentUserStatus();
        List<RescheduleSuggestionService.RescheduleSuggestionResponse> suggestions =
                rescheduleSuggestionService.getCurrentUserSuggestions();

        return new DashboardSummaryResponse(
                week,
                goals,
                focus,
                new DashboardSyncStatusResponse(syncSnapshot.data(), syncSnapshot.meta()),
                suggestions,
                buildMetrics(week, goals)
        );
    }

    private DashboardMetricsResponse buildMetrics(
            ScheduleService.WeekScheduleResponse week,
            List<DashboardGoalResponse> goals
    ) {
        int averageGoalProgress = goals.isEmpty()
                ? 0
                : Math.round((float) goals.stream().mapToInt(DashboardGoalResponse::progress).sum() / goals.size());
        DashboardGoalResponse topGoal = goals.stream()
                .max((left, right) -> Integer.compare(left.progress(), right.progress()))
                .orElse(null);

        int blockCount = week.week().stream()
                .mapToInt(day -> day.blocks().size())
                .sum();
        int growthBlockCount = week.week().stream()
                .flatMap(day -> day.blocks().stream())
                .mapToInt(block -> "GROWTH".equals(block.category()) ? 1 : 0)
                .sum();
        int weeklyShapeScore = blockCount == 0 ? 0 : Math.round((float) growthBlockCount * 100 / blockCount);

        return new DashboardMetricsResponse(
                averageGoalProgress,
                weeklyShapeScore,
                blockCount,
                growthBlockCount,
                topGoal
        );
    }

    public record DashboardSummaryResponse(
            ScheduleService.WeekScheduleResponse week,
            List<DashboardGoalResponse> goals,
            FocusService.FocusCurrentView focus,
            DashboardSyncStatusResponse sync,
            List<RescheduleSuggestionService.RescheduleSuggestionResponse> suggestions,
            DashboardMetricsResponse metrics
    ) {
    }

    public record DashboardMetricsResponse(
            int averageGoalProgress,
            int weeklyShapeScore,
            int scheduleBlockCount,
            int growthBlockCount,
            DashboardGoalResponse topGoal
    ) {
    }

    public record DashboardSyncStatusResponse(
            SyncOrchestrationService.SyncStatusResponse data,
            Map<String, Object> meta
    ) {
    }

    public record DashboardGoalResponse(
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
        static DashboardGoalResponse from(Goal goal) {
            return new DashboardGoalResponse(
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
