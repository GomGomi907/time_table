package com.timetable.operator.settings.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import com.timetable.operator.settings.domain.UserPreferences;
import com.timetable.operator.settings.infrastructure.UserPreferencesRepository;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserPreferencesRepository userPreferencesRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AppProperties appProperties;

    @Transactional
    public UserPreferences getOrCreatePreferences() {
        AppUser user = currentUserProvider.getCurrentUser();
        return userPreferencesRepository.findByUserId(user.getId())
                .orElseGet(() -> createDefaults(user));
    }

    @Transactional
    public UserPreferences update(SettingsUpdateRequest request) {
        AppUser user = currentUserProvider.getCurrentUser();
        UserPreferences preferences = getOrCreatePreferences();
        if (request.quietHoursStart() != null) {
            preferences.setQuietHoursStart(request.quietHoursStart());
        }
        if (request.quietHoursEnd() != null) {
            preferences.setQuietHoursEnd(request.quietHoursEnd());
        }
        if (request.bufferMinutes() != null) {
            preferences.setBufferMinutes(request.bufferMinutes());
        }
        if (request.overtimeTriggerMinutes() != null) {
            preferences.setOvertimeTriggerMinutes(request.overtimeTriggerMinutes());
        }
        if (request.openGapTriggerMinutes() != null) {
            preferences.setOpenGapTriggerMinutes(request.openGapTriggerMinutes());
        }
        if (request.preferredFocusMinutes() != null) {
            preferences.setPreferredFocusMinutes(request.preferredFocusMinutes());
        }
        if (request.breakBufferMinutes() != null) {
            preferences.setBreakBufferMinutes(request.breakBufferMinutes());
        }
        if (request.interventionFrequency() != null && !request.interventionFrequency().isBlank()) {
            preferences.setInterventionFrequency(request.interventionFrequency());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            user.setTimezone(request.timezone().trim());
        }
        if (request.autoRescheduleEnabled() != null) {
            user.setAutoRescheduleEnabled(request.autoRescheduleEnabled());
        }
        if (request.focusAutoEnterEnabled() != null) {
            user.setFocusAutoEnterEnabled(request.focusAutoEnterEnabled());
        }
        return userPreferencesRepository.save(preferences);
    }

    private UserPreferences createDefaults(AppUser user) {
        UserPreferences preferences = new UserPreferences();
        preferences.setUserId(user.getId());
        preferences.setQuietHoursStart(appProperties.schedule().defaultQuietHoursStart());
        preferences.setQuietHoursEnd(appProperties.schedule().defaultQuietHoursEnd());
        preferences.setBufferMinutes(appProperties.schedule().defaultBufferMinutes());
        preferences.setOvertimeTriggerMinutes(appProperties.schedule().defaultOvertimeTriggerMinutes());
        preferences.setOpenGapTriggerMinutes(appProperties.schedule().defaultOpenGapTriggerMinutes());
        preferences.setPreferredFocusMinutes(45);
        preferences.setBreakBufferMinutes(10);
        preferences.setInterventionFrequency("balanced");
        return userPreferencesRepository.save(preferences);
    }

    public record SettingsUpdateRequest(
            LocalTime quietHoursStart,
            LocalTime quietHoursEnd,
            Integer bufferMinutes,
            Integer overtimeTriggerMinutes,
            Integer openGapTriggerMinutes,
            Integer preferredFocusMinutes,
            Integer breakBufferMinutes,
            String interventionFrequency,
            String timezone,
            Boolean autoRescheduleEnabled,
            Boolean focusAutoEnterEnabled
    ) {
    }
}
