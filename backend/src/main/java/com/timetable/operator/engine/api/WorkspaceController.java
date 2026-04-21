package com.timetable.operator.engine.api;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.engine.application.FocusSessionService;
import com.timetable.operator.engine.application.RuleEngineService;
import com.timetable.operator.engine.domain.FocusSession;
import com.timetable.operator.intervention.api.InterventionController;
import com.timetable.operator.intervention.application.InterventionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final FocusSessionService focusSessionService;
    private final RuleEngineService ruleEngineService;
    private final InterventionService interventionService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/status")
    public WorkspaceStatusResponse getStatus() {
        AppUser user = currentUserProvider.getCurrentUser();
        
        // 상태 조회 시점에 룰 엔진 가동 (실시간성 확보)
        ruleEngineService.evaluateRules(user);
        
        return new WorkspaceStatusResponse(
            focusSessionService.getActiveSession().map(FocusSessionResponse::from).orElse(null),
            interventionService.getPendingInterventions().stream().map(InterventionController.InterventionResponse::from).toList()
        );
    }

    @PostMapping("/session/start")
    public FocusSessionResponse startSession(@RequestParam UUID blockId) {
        return FocusSessionResponse.from(focusSessionService.startSession(blockId));
    }

    @PostMapping("/session/{sessionId}/pause")
    public void pauseSession(@PathVariable UUID sessionId) {
        focusSessionService.pauseSession(sessionId);
    }

    @PostMapping("/session/{sessionId}/resume")
    public void resumeSession(@PathVariable UUID sessionId) {
        focusSessionService.resumeSession(sessionId);
    }

    @PostMapping("/session/{sessionId}/complete")
    public void completeSession(@PathVariable UUID sessionId) {
        focusSessionService.completeSession(sessionId);
    }

    public record WorkspaceStatusResponse(
        FocusSessionResponse activeSession,
        List<InterventionController.InterventionResponse> pendingInterventions
    ) {}

    public record FocusSessionResponse(
        String id,
        String scheduledBlockId,
        String status,
        boolean isPaused
    ) {
        public static FocusSessionResponse from(FocusSession s) {
            return new FocusSessionResponse(
                s.getId().toString(),
                s.getScheduledBlockId().toString(),
                s.getStatus().name(),
                s.isPaused()
            );
        }
    }
}
