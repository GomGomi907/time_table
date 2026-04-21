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

    @GetMapping("/google/start")
    public GoogleStartResponse startGoogleLogin(HttpServletRequest request) {
        String origin = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();

        if (!appProperties.googleOauthEnabled()) {
            return new GoogleStartResponse(
                    true,
                    origin + "/api/auth/mock/login",
                    "Google Client Secret이 설정되지 않아 개발용 Mock 로그인으로 진행합니다."
            );
        }

        return new GoogleStartResponse(true, origin + "/oauth2/authorization/google", null);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.setHeader("Clear-Site-Data", "\"cookies\"");
        return ResponseEntity.noContent().location(URI.create("/")).build();
    }

    public record GoogleStartResponse(
            boolean enabled,
            String url,
            String message
    ) {
    }
}
