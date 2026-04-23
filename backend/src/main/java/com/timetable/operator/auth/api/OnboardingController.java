package com.timetable.operator.auth.api;

import com.timetable.operator.auth.application.OnboardingService;
import com.timetable.operator.auth.application.OnboardingService.OnboardingAnswersRequest;
import com.timetable.operator.auth.application.OnboardingService.OnboardingAnswersResponse;
import com.timetable.operator.auth.application.OnboardingService.OnboardingBootstrapResponse;
import com.timetable.operator.auth.application.OnboardingService.OnboardingCompletionRequest;
import com.timetable.operator.auth.application.OnboardingService.OnboardingCompletionResponse;
import com.timetable.operator.auth.application.OnboardingService.OnboardingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/status")
    public OnboardingStatusResponse getStatus() {
        return onboardingService.getStatus();
    }

    @PostMapping("/bootstrap")
    public OnboardingBootstrapResponse bootstrap() {
        return onboardingService.bootstrap();
    }

    @PostMapping("/answers")
    public OnboardingAnswersResponse saveAnswers(@RequestBody OnboardingAnswersRequest request) {
        return onboardingService.saveAnswers(request);
    }

    @PostMapping("/complete")
    public OnboardingCompletionResponse complete(@RequestBody(required = false) OnboardingCompletionRequest request) {
        return onboardingService.complete(request == null ? new OnboardingCompletionRequest(false, null) : request);
    }
}
