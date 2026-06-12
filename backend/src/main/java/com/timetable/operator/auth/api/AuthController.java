package com.timetable.operator.auth.api;

import com.timetable.operator.auth.application.AuthService;
import com.timetable.operator.common.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppProperties appProperties;

    @GetMapping("/session")
    public AuthService.SessionResponse getSession() {
        return authService.getSession();
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse getCsrfToken(CsrfToken csrfToken) {
        return new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );
    }

    @GetMapping("/google/start")
    public GoogleStartResponse startGoogleLogin() {
        if (!appProperties.googleOauthEnabled() && appProperties.mockLoginEnabled()) {
            return new GoogleStartResponse(
                    true,
                    "/api/auth/mock/login",
                    "Google Client Secret이 설정되지 않아 개발용 Mock 로그인으로 진행합니다."
            );
        }

        if (appProperties.googleOauthEnabled()) {
            return new GoogleStartResponse(true, "/oauth2/authorization/google", null);
        }

        return new GoogleStartResponse(
                false,
                null,
                "Google OAuth 자격 증명이 설정되지 않았고 개발용 Mock 로그인도 비활성화되어 있습니다."
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.setHeader("Clear-Site-Data", "\"cookies\"");
        return ResponseEntity.noContent().location(URI.create("/")).build();
    }

    @PostMapping("/google/disconnect")
    public AuthService.SessionResponse disconnectGoogle() {
        return authService.disconnectGoogle();
    }

    @PostMapping("/account/delete")
    public ResponseEntity<Void> deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        authService.deleteCurrentAccount();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.setHeader("Clear-Site-Data", "\"cookies\"");
        return ResponseEntity.noContent().location(URI.create("/")).build();
    }

    public record GoogleStartResponse(
            boolean enabled,
            String url,
            String message
    ) {
    }

    public record CsrfTokenResponse(
            String headerName,
            String parameterName,
            String token
    ) {
    }
}
