package com.timetable.operator.focus.api;

import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.focus.application.FocusService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/focus")
@RequiredArgsConstructor
public class FocusController {

    private final FocusService focusService;

    @GetMapping("/current")
    public ApiEnvelope<FocusService.FocusCurrentView> getCurrentFocus() {
        return ApiEnvelope.ok(focusService.getCurrentFocus());
    }

    @GetMapping("/recommendations")
    public ApiEnvelope<FocusService.FocusCurrentView> getRecommendations() {
        return ApiEnvelope.ok(focusService.getCurrentFocus());
    }

    @PostMapping("/current/start-recommended-task")
    public ApiEnvelope<FocusService.FocusCurrentView> startRecommendedTask(@RequestBody Map<String, String> request) {
        return ApiEnvelope.ok(focusService.startRecommendedTask(UUID.fromString(request.get("taskId"))));
    }

    @PostMapping("/current/complete")
    public ApiEnvelope<FocusService.FocusCurrentView> completeCurrent(
            @Valid @RequestBody FocusService.CompleteFocusRequest request
    ) {
        return ApiEnvelope.ok(focusService.completeCurrent(request));
    }

    @PostMapping("/current/postpone")
    public ApiEnvelope<FocusService.FocusCurrentView> postponeCurrent(
            @Valid @RequestBody FocusService.PostponeFocusRequest request
    ) {
        return ApiEnvelope.ok(focusService.postponeCurrent(request));
    }

    @PostMapping("/current/confirm-overrun")
    public ApiEnvelope<FocusService.FocusCurrentView> confirmOverrun(
            @Valid @RequestBody FocusService.ConfirmOverrunRequest request
    ) {
        return ApiEnvelope.ok(focusService.confirmOverrun(request));
    }
}
