package com.timetable.operator.auth.application;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.domain.CalendarSyncRun;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.calendar.infrastructure.CalendarSyncRunRepository;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final AppUserRepository appUserRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarSyncRunRepository calendarSyncRunRepository;
    private final AppProperties appProperties;
    private final GoogleTokenRevocationService googleTokenRevocationService;

    public SessionResponse getSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OAuth2User;

        if (!authenticated) {
            return unauthenticatedSession();
        }

        AppUser user = currentUserProvider.getCurrentUser();
        Optional<CalendarConnection> connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google");
        Optional<CalendarSyncRun> latestSync = calendarSyncRunRepository.findTopByUserIdOrderByStartedAtDesc(user.getId());

        return new SessionResponse(
                authenticated,
                user.getId().toString(),
                user.getEmail(),
                user.getDisplayName(),
                user.getTimezone(),
                user.isAutoRescheduleEnabled(),
                user.isFocusAutoEnterEnabled(),
                connection.map(CalendarConnection::getStatus).orElse(CalendarConnectionStatus.NOT_CONNECTED).name(),
                connection.map(CalendarConnection::isCalendarWriteEnabled).orElse(false),
                connection.map(CalendarConnection::isTasksWriteEnabled).orElse(false),
                connection.map(this::capabilityStatus).orElse("not_connected"),
                latestSync.map(CalendarSyncRun::getFinishedAt).orElse(null),
                appProperties.frontendBaseUrl() + "/auth/callback"
        );
    }

    private SessionResponse unauthenticatedSession() {
        return new SessionResponse(
                false,
                "",
                "",
                "",
                "Asia/Seoul",
                false,
                false,
                CalendarConnectionStatus.NOT_CONNECTED.name(),
                false,
                false,
                "not_connected",
                null,
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
            applyGrantedScopes(connection, authorizedClient.getAccessToken().getScopes());
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

    @Transactional
    public SessionResponse disconnectGoogle() {
        AppUser user = currentUserProvider.getCurrentUser();
        calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .ifPresent(connection -> {
                    googleTokenRevocationService.revokeIfConfigured(
                            connection.getRefreshToken(),
                            connection.getAccessToken()
                    );
                    connection.setStatus(CalendarConnectionStatus.NOT_CONNECTED);
                    connection.setAccessToken(null);
                    connection.setRefreshToken(null);
                    connection.setTokenExpiresAt(null);
                    connection.setLastSyncError(null);
                    connection.setGrantedScopes("");
                    connection.setCalendarReadEnabled(false);
                    connection.setCalendarWriteEnabled(false);
                    connection.setTasksReadEnabled(false);
                    connection.setTasksWriteEnabled(false);
                    connection.setCapabilityCheckedAt(Instant.now());
                    connection.setCapabilityStatus("not_connected");
                    connection.setCapabilityError("Google connection was disconnected by the user.");
                    calendarConnectionRepository.save(connection);
                });

        return getSession();
    }

    @Transactional
    public void deleteCurrentAccount() {
        AppUser user = currentUserProvider.getCurrentUser();
        calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .ifPresent(connection -> googleTokenRevocationService.revokeIfConfigured(
                        connection.getRefreshToken(),
                        connection.getAccessToken()
                ));
        appUserRepository.deleteById(user.getId());
        appUserRepository.flush();
    }

    private void applyGrantedScopes(CalendarConnection connection, Set<String> scopes) {
        Set<String> normalizedScopes = scopes == null ? Set.of() : scopes;
        String grantedScopes = normalizedScopes.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));

        boolean calendarRead = hasAnyScope(
                normalizedScopes,
                "https://www.googleapis.com/auth/calendar",
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.events.owned",
                "https://www.googleapis.com/auth/calendar.app.created",
                "https://www.googleapis.com/auth/calendar.readonly"
        );
        boolean calendarWrite = hasAnyScope(
                normalizedScopes,
                "https://www.googleapis.com/auth/calendar",
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.events.owned",
                "https://www.googleapis.com/auth/calendar.app.created"
        );
        boolean tasksRead = hasAnyScope(
                normalizedScopes,
                "https://www.googleapis.com/auth/tasks",
                "https://www.googleapis.com/auth/tasks.readonly"
        );
        boolean tasksWrite = hasAnyScope(normalizedScopes, "https://www.googleapis.com/auth/tasks");

        connection.setGrantedScopes(grantedScopes);
        connection.setCalendarReadEnabled(calendarRead);
        connection.setCalendarWriteEnabled(calendarWrite);
        connection.setTasksReadEnabled(tasksRead);
        connection.setTasksWriteEnabled(tasksWrite);
        connection.setCapabilityCheckedAt(Instant.now());
        connection.setCapabilityStatus(calendarWrite && tasksWrite ? "write_enabled" : "read_only_token");
        connection.setCapabilityError(calendarWrite && tasksWrite ? null : "Reconnect with Calendar/Tasks write scopes.");
    }

    private boolean hasAnyScope(Set<String> scopes, String... candidates) {
        for (String candidate : candidates) {
            if (scopes.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String capabilityStatus(CalendarConnection connection) {
        if (connection.getCapabilityStatus() != null && !connection.getCapabilityStatus().isBlank()) {
            return connection.getCapabilityStatus();
        }
        if (connection.isCalendarWriteEnabled() || connection.isTasksWriteEnabled()) {
            return "write_enabled";
        }
        if (connection.getStatus() == CalendarConnectionStatus.CONNECTED) {
            return "read_only_token";
        }
        return connection.getStatus().name().toLowerCase();
    }

    public record SessionResponse(
            boolean authenticated,
            String userId,
            String email,
            String displayName,
            String timezone,
            boolean autoRescheduleEnabled,
            boolean focusAutoEnterEnabled,
            String googleConnectionStatus,
            boolean calendarWriteEnabled,
            boolean tasksWriteEnabled,
            String googleCapabilityStatus,
            Instant lastSyncAt,
            String callbackUrl
    ) {
    }
}
