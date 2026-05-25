package com.timetable.operator.agent.api;

import com.timetable.operator.agent.application.ChatCommandOrchestrationService;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.api.ApiResponses;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.goals.api.GoalController;
import com.timetable.operator.goals.application.GoalService;
import com.timetable.operator.settings.api.SettingsController;
import com.timetable.operator.settings.application.SettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatCommandController {

    private final ChatCommandOrchestrationService chatCommandOrchestrationService;
    private final SettingsService settingsService;
    private final GoalService goalService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/api/chat/command")
    public ResponseEntity<ApiEnvelope<ChatCommandOrchestrationService.ChatCommandResponse>> handleCommand(
            @Valid @RequestBody ChatCommandOrchestrationService.ChatCommandRequest request
    ) {
        return ApiResponses.ok(chatCommandOrchestrationService.handle(request));
    }

    @PostMapping("/api/chat/settings")
    public ResponseEntity<ApiEnvelope<SettingsController.SettingsResponse>> updateSettingsFromChat(
            @RequestBody SettingsService.SettingsUpdateRequest request
    ) {
        return ApiResponses.ok(SettingsController.SettingsResponse.from(
                settingsService.update(request),
                currentUserProvider.getCurrentUser()
        ));
    }

    @PostMapping("/api/chat/progress-update")
    public ResponseEntity<ApiEnvelope<GoalController.GoalResponse>> updateProgressFromChat(
            @Valid @RequestBody ChatProgressUpdateRequest request
    ) {
        return ApiResponses.ok(GoalController.GoalResponse.from(
                goalService.updateProgress(request.goalId(), request.toProgressUpdateRequest())
        ));
    }

    public record ChatProgressUpdateRequest(
            UUID goalId,
            java.math.BigDecimal deltaValue,
            String reason
    ) {
        GoalService.ProgressUpdateRequest toProgressUpdateRequest() {
            return new GoalService.ProgressUpdateRequest(deltaValue, reason);
        }
    }
}
