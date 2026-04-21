package com.timetable.operator.settings.api;

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

    @GetMapping
    public SettingsResponse getSettings() {
        return SettingsResponse.from(settingsService.getOrCreatePreferences());
    }

    @PutMapping
    public SettingsResponse updateSettings(@RequestBody SettingsUpdateRequest request) {
        return SettingsResponse.from(settingsService.update(request));
    }

    public record SettingsResponse(
            String id,
            String quietHoursStart,
            String quietHoursEnd,
            Integer bufferMinutes,
            Integer overtimeTriggerMinutes,
            Integer openGapTriggerMinutes,
            String interventionFrequency
    ) {
        static SettingsResponse from(UserPreferences preferences) {
            return new SettingsResponse(
                    preferences.getId().toString(),
                    preferences.getQuietHoursStart().toString(),
                    preferences.getQuietHoursEnd().toString(),
                    preferences.getBufferMinutes(),
                    preferences.getOvertimeTriggerMinutes(),
                    preferences.getOpenGapTriggerMinutes(),
                    preferences.getInterventionFrequency()
            );
        }
    }
}
