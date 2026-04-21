package com.timetable.operator.auth.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.domain.CalendarSyncRun;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.calendar.infrastructure.CalendarSyncRunRepository;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarSyncRunRepository calendarSyncRunRepository;
    private final AppProperties appProperties;

    public SessionResponse getSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OAuth2User;

        AppUser user = currentUserProvider.getCurrentUser();
        Optional<CalendarConnection> connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google");
        Optional<CalendarSyncRun> latestSync = calendarSyncRunRepository.findTopByUserIdOrderByStartedAtDesc(user.getId());

        return new SessionResponse(
                authenticated,
                user.getId().toString(),
                user.getEmail(),
                user.getDisplayName(),
                connection.map(CalendarConnection::getStatus).orElse(CalendarConnectionStatus.NOT_CONNECTED).name(),
                latestSync.map(CalendarSyncRun::getFinishedAt).orElse(null),
                appProperties.frontendBaseUrl() + "/auth/callback"
        );
    }

    @Transactional
    public void handleOauthLogin(AppUser user, OAuth2AuthorizedClient authorizedClient) {
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseGet(CalendarConnection::new);

        connection.setUserId(user.getId());
        connection.setProvider("google");
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        connection.setEmail(user.getEmail());
        connection.setGoogleSubject(user.getGoogleSubject());

        if (authorizedClient != null) {
            connection.setAccessToken(authorizedClient.getAccessToken().getTokenValue());
            connection.setTokenExpiresAt(authorizedClient.getAccessToken().getExpiresAt());
            if (authorizedClient.getRefreshToken() != null) {
                connection.setRefreshToken(authorizedClient.getRefreshToken().getTokenValue());
            }
        }

        calendarConnectionRepository.save(connection);
    }

    @Transactional
    public void markCalendarSyncResult(AppUser user, boolean success, String errorMessage) {
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseGet(CalendarConnection::new);

        connection.setUserId(user.getId());
        connection.setProvider("google");
        connection.setStatus(success ? CalendarConnectionStatus.CONNECTED : CalendarConnectionStatus.DEGRADED);
        connection.setEmail(user.getEmail());
        connection.setGoogleSubject(user.getGoogleSubject());
        connection.setLastSuccessfulSyncAt(success ? Instant.now() : connection.getLastSuccessfulSyncAt());
        connection.setLastSyncError(errorMessage);
        calendarConnectionRepository.save(connection);
    }

    public record SessionResponse(
            boolean authenticated,
            String userId,
            String email,
            String displayName,
            String googleConnectionStatus,
            Instant lastSyncAt,
            String callbackUrl
    ) {
    }
}
