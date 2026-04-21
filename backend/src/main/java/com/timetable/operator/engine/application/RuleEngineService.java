package com.timetable.operator.engine.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.engine.domain.FocusSession;
import com.timetable.operator.engine.domain.FocusSessionStatus;
import com.timetable.operator.engine.infrastructure.FocusSessionRepository;
import com.timetable.operator.intervention.domain.Intervention;
import com.timetable.operator.intervention.domain.InterventionStatus;
import com.timetable.operator.intervention.domain.InterventionTriggerType;
import com.timetable.operator.intervention.infrastructure.InterventionRepository;
import com.timetable.operator.schedule.domain.ScheduleBlock;
import com.timetable.operator.schedule.infrastructure.ScheduleBlockRepository;
import com.timetable.operator.settings.domain.UserPreferences;
import com.timetable.operator.settings.infrastructure.UserPreferencesRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final FocusSessionRepository focusSessionRepository;
    private final InterventionRepository interventionRepository;
    private final ScheduleBlockRepository scheduleBlockRepository;
    private final UserPreferencesRepository userPreferencesRepository;

    @Transactional
    public List<Intervention> evaluateRules(AppUser user) {
        UserPreferences prefs = userPreferencesRepository.findByUserId(user.getId())
            .orElseGet(() -> createDefaultPreferences(user));
        
        List<Intervention> newInterventions = new ArrayList<>();
        
        // 1. Overtime Rule
        evaluateOvertimeRule(user, prefs).ifPresent(newInterventions::add);
        
        // 2. Gap Rule
        evaluateGapRule(user, prefs).ifPresent(newInterventions::add);
        
        return interventionRepository.saveAll(newInterventions);
    }

    private java.util.Optional<Intervention> evaluateOvertimeRule(AppUser user, UserPreferences prefs) {
        return focusSessionRepository.findByUserIdAndStatus(user.getId(), FocusSessionStatus.ACTIVE)
            .flatMap(session -> scheduleBlockRepository.findById(session.getScheduledBlockId())
                .filter(block -> {
                    LocalTime now = LocalTime.now();
                    if (now.isAfter(block.getEndTime())) {
                        long diffMinutes = Duration.between(block.getEndTime(), now).toMinutes();
                        return diffMinutes >= prefs.getOvertimeTriggerMinutes();
                    }
                    return false;
                })
                .map(block -> {
                    // 이미 대기 중인 동일 타입 인터벤션이 있는지 확인
                    if (hasPendingIntervention(user.getId(), InterventionTriggerType.OVERTIME)) {
                        return null;
                    }

                    Intervention intervention = new Intervention();
                    intervention.setUserId(user.getId());
                    intervention.setTriggerType(InterventionTriggerType.OVERTIME);
                    intervention.setSummary("현재 진행 중인 '" + block.getActivity() + "' 일정이 계획보다 늦어지고 있습니다.");
                    intervention.setRecommendation("일정을 15분 연장하거나, 다음 일정으로 미룰까요?");
                    intervention.setImpactAnalysis("연장 시 오늘 남은 일정이 전체적으로 15분씩 뒤로 밀립니다.");
                    intervention.setAffectedBlockIds(block.getId().toString());
                    intervention.setStatus(InterventionStatus.PENDING);
                    intervention.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
                    return intervention;
                }));
    }

    private java.util.Optional<Intervention> evaluateGapRule(AppUser user, UserPreferences prefs) {
        // 간단한 갭 감지 로직 (현재 시각 이후의 가장 가까운 블록 확인)
        // 실제 운영 시 더 복잡한 정렬과 필터링 필요
        return java.util.Optional.empty(); // v1에서는 지연 감지 우선
    }

    private boolean hasPendingIntervention(UUID userId, InterventionTriggerType type) {
        return interventionRepository.findByUserIdAndStatus(userId, InterventionStatus.PENDING)
            .stream()
            .anyMatch(i -> i.getTriggerType() == type);
    }

    private UserPreferences createDefaultPreferences(AppUser user) {
        UserPreferences prefs = new UserPreferences();
        prefs.setUserId(user.getId());
        prefs.setQuietHoursStart(LocalTime.of(23, 0));
        prefs.setQuietHoursEnd(LocalTime.of(7, 0));
        prefs.setBufferMinutes(5);
        prefs.setOvertimeTriggerMinutes(10);
        prefs.setOpenGapTriggerMinutes(30);
        prefs.setInterventionFrequency("NORMAL");
        return userPreferencesRepository.save(prefs);
    }
}
