package com.timetable.operator.intervention.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.intervention.domain.Intervention;
import com.timetable.operator.intervention.domain.InterventionStatus;
import com.timetable.operator.intervention.infrastructure.InterventionRepository;
import com.timetable.operator.schedule.application.ScheduleService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InterventionService {

    private final InterventionRepository interventionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ScheduleService scheduleService;

    @Transactional(readOnly = true)
    public List<Intervention> getPendingInterventions() {
        AppUser user = currentUserProvider.getCurrentUser();
        return interventionRepository.findByUserIdAndStatus(user.getId(), InterventionStatus.PENDING);
    }

    @Transactional
    public void decide(UUID interventionId, DecisionRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        Intervention intervention = interventionRepository.findByIdAndUserId(interventionId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Intervention not found"));

        if (intervention.getStatus() != InterventionStatus.PENDING) {
            throw new IllegalStateException("Intervention is already processed");
        }

        if (request.accepted()) {
            intervention.setStatus(InterventionStatus.ACCEPTED);
            applyAdjustment(intervention);
        } else {
            intervention.setStatus(InterventionStatus.REJECTED);
        }

        interventionRepository.save(intervention);
    }

    private void applyAdjustment(Intervention intervention) {
        if (intervention.getTriggerType() == InterventionTriggerType.OVERTIME) {
            String blockIds = intervention.getAffectedBlockIds();
            if (blockIds != null && !blockIds.isBlank()) {
                UUID blockId = UUID.fromString(blockIds.split(",")[0]);
                scheduleService.updateBlock(blockId, extendBlockRequest(blockId));
            }
        }
    }

    private ScheduleService.ScheduleBlockWriteRequest extendBlockRequest(UUID blockId) {
        AppUser user = currentUserProvider.getCurrentUser();
        var block = scheduleService.getWeeklySchedule().week().stream()
            .flatMap(d -> d.blocks().stream())
            .filter(b -> b.id().equals(blockId.toString()))
            .findFirst()
            .orElseThrow();
            
        java.time.LocalTime currentEnd = java.time.LocalTime.parse(block.endTime());
        java.time.LocalTime newEnd = currentEnd.plusMinutes(30);
        
        return new ScheduleService.ScheduleBlockWriteRequest(
            java.time.DayOfWeek.valueOf(java.time.LocalDate.now().getDayOfWeek().name()), // Simple assumption for today
            java.time.LocalTime.parse(block.startTime()),
            newEnd,
            block.activity(),
            com.timetable.operator.schedule.domain.ScheduleCategory.valueOf(block.category()),
            block.note()
        );
    }

    public record DecisionRequest(boolean accepted) {}
}
