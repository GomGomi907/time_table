package com.timetable.operator.goals.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.goals.domain.Goal;
import com.timetable.operator.goals.domain.GoalCategory;
import com.timetable.operator.goals.domain.GoalStatus;
import com.timetable.operator.goals.infrastructure.GoalRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
        return goalRepository.findByUserId(user.getId()).stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt))
                .toList();
    }

    @Transactional
    public Goal createGoal(CreateGoalRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Goal goal = new Goal();
        goal.setUserId(user.getId());
        goal.setParentGoalId(request.parentId());
        goal.setTitle(request.title().trim());
        goal.setDescription(blankToNull(request.description()));
        goal.setCategory(request.category() == null ? GoalCategory.GROWTH : request.category());
        goal.setStatus(request.status() == null ? GoalStatus.PENDING : request.status());
        goal.setProgress(request.progress() == null ? 0 : request.progress());
        return goalRepository.save(goal);
    }

    private void seedDefaultsIfNeeded(AppUser user) {
        if (goalRepository.existsByUserId(user.getId())) {
            return;
        }

        Goal roadmap = new Goal();
        roadmap.setUserId(user.getId());
        roadmap.setTitle("2026 성장 로드맵");
        roadmap.setDescription("일, 영어, AI 학습 루틴을 안정적으로 쌓는다.");
        roadmap.setCategory(GoalCategory.GROWTH);
        roadmap.setStatus(GoalStatus.IN_PROGRESS);
        roadmap.setProgress(35);
        roadmap = goalRepository.save(roadmap);

        Goal english = new Goal();
        english.setUserId(user.getId());
        english.setParentGoalId(roadmap.getId());
        english.setTitle("영어 루틴 유지");
        english.setDescription("월/수 영어 학습 블록을 지키고 주간 복습을 누적한다.");
        english.setCategory(GoalCategory.GROWTH);
        english.setStatus(GoalStatus.IN_PROGRESS);
        english.setProgress(45);

        Goal ai = new Goal();
        ai.setUserId(user.getId());
        ai.setParentGoalId(roadmap.getId());
        ai.setTitle("AI 포트폴리오 구축");
        ai.setDescription("야간 기술 탐색 시간을 서비스 실험으로 연결한다.");
        ai.setCategory(GoalCategory.CAREER);
        ai.setStatus(GoalStatus.IN_PROGRESS);
        ai.setProgress(25);

        Goal life = new Goal();
        life.setUserId(user.getId());
        life.setTitle("생활 정리 루틴");
        life.setDescription("이사/정산/주간 리뷰 같은 관리 작업을 누락하지 않는다.");
        life.setCategory(GoalCategory.OTHER);
        life.setStatus(GoalStatus.PENDING);
        life.setProgress(20);

        goalRepository.saveAll(List.of(english, ai, life));
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
            @Min(0) @Max(100) Integer progress
    ) {
    }
}
