package com.timetable.operator.auth.api;

import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.calendar.domain.CalendarConnection;
import com.timetable.operator.calendar.domain.CalendarConnectionStatus;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.auth.mock-login-enabled", havingValue = "true")
public class MockAuthController {

    private final AppProperties appProperties;
    private final CurrentUserProvider currentUserProvider;
    private final CalendarConnectionRepository calendarConnectionRepository;

    @GetMapping("/login")
    public void mockLogin(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "false") boolean connectGoogle,
            @RequestParam(defaultValue = "false") boolean writeCapable,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String resolvedEmail = resolveMockValue(email, appProperties.auth().defaultUserEmail());
        String resolvedName = resolveMockValue(name, "Demo User (Mock)");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "mock-google-id:" + resolvedEmail.toLowerCase(Locale.ROOT));
        attributes.put("name", resolvedName);
        attributes.put("email", resolvedEmail);
        attributes.put("picture", "https://ui-avatars.com/api/?name=Demo+User");

        DefaultOAuth2User principal = new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "name"
        );

        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal,
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                "google"
        );

        SecurityContextHolder.getContext().setAuthentication(token);
        request.getSession(true).setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
        if (connectGoogle) {
            seedMockGoogleConnection(writeCapable);
        }

        response.sendRedirect(appProperties.frontendBaseUrl() + "/auth/callback?status=success&mock=true");
    }

    private void seedMockGoogleConnection(boolean writeCapable) {
        AppUser user = currentUserProvider.getCurrentUser();
        CalendarConnection connection = calendarConnectionRepository.findByUserIdAndProvider(user.getId(), "google")
                .orElseGet(CalendarConnection::new);
        connection.setUserId(user.getId());
        connection.setProvider("google");
        connection.setStatus(CalendarConnectionStatus.CONNECTED);
        connection.setGoogleSubject(user.getGoogleSubject());
        connection.setEmail(user.getEmail());
        connection.setAccessToken("mock-google-access-token");
        connection.setRefreshToken("mock-google-refresh-token");
        connection.setTokenExpiresAt(Instant.now().plusSeconds(3_600));
        connection.setGrantedScopes(writeCapable
                ? "https://www.googleapis.com/auth/calendar.events.owned,https://www.googleapis.com/auth/tasks"
                : "https://www.googleapis.com/auth/calendar.readonly,https://www.googleapis.com/auth/tasks.readonly");
        connection.setCalendarReadEnabled(true);
        connection.setTasksReadEnabled(true);
        connection.setCalendarWriteEnabled(writeCapable);
        connection.setTasksWriteEnabled(writeCapable);
        connection.setCapabilityCheckedAt(Instant.now());
        connection.setCapabilityStatus(writeCapable ? "write_enabled" : "read_only_token");
        connection.setCapabilityError(writeCapable ? null : "Reconnect with Calendar/Tasks write scopes.");
        calendarConnectionRepository.save(connection);
    }

    private String resolveMockValue(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        return candidate.trim();
    }
}
