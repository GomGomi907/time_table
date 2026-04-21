package com.timetable.operator.auth.api;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.settings.application.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final SettingsService settingsService;

    @GetMapping("/status")
    public OnboardingStatusResponse getStatus() {
        AppUser user = currentUserProvider.getCurrentUser();
        boolean googleConnected = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .map(connection -> connection.getStatus() == CalendarConnectionStatus.CONNECTED)
                .orElse(false);

        settingsService.getOrCreatePreferences();

        String nextStep = googleConnected ? "routines" : "connect_google";
        return new OnboardingStatusResponse(
                googleConnected,
                false,
                false,
                true,
                false,
                nextStep
        );
    }

    public record OnboardingStatusResponse(
            boolean googleConnected,
            boolean routinesReady,
            boolean goalsReady,
            boolean preferencesReady,
            boolean scheduleReady,
            String nextStep
    ) {
    }
}
