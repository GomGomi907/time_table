package com.timetable.operator.settings.api;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.api.ApiEnvelope;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.settings.application.SettingsService;
import com.timetable.operator.settings.application.SettingsService.SettingsUpdateRequest;
import com.timetable.operator.settings.domain.UserPreferences;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public ApiEnvelope<SettingsResponse> getSettings() {
        return ApiEnvelope.ok(SettingsResponse.from(
                settingsService.getOrCreatePreferences(),
                currentUserProvider.getCurrentUser()
        ));
    }

    @PutMapping
    public ApiEnvelope<SettingsResponse> updateSettings(@RequestBody SettingsUpdateRequest request) {
        return ApiEnvelope.ok(SettingsResponse.from(
                settingsService.update(request),
                currentUserProvider.getCurrentUser()
        ));
    }

    public record SettingsResponse(
            String id,
            String quietHoursStart,
            String quietHoursEnd,
            Integer bufferMinutes,
            Integer overtimeTriggerMinutes,
            Integer openGapTriggerMinutes,
            String interventionFrequency,
            String timezone,
            boolean autoRescheduleEnabled,
            boolean focusAutoEnterEnabled
    ) {
        static SettingsResponse from(UserPreferences preferences, AppUser user) {
            return new SettingsResponse(
                    preferences.getId().toString(),
                    preferences.getQuietHoursStart().toString(),
                    preferences.getQuietHoursEnd().toString(),
                    preferences.getBufferMinutes(),
                    preferences.getOvertimeTriggerMinutes(),
                    preferences.getOpenGapTriggerMinutes(),
                    preferences.getInterventionFrequency(),
                    user.getTimezone(),
                    user.isAutoRescheduleEnabled(),
                    user.isFocusAutoEnterEnabled()
            );
        }
    }
}
