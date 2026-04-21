package com.timetable.operator.auth.api;

import com.timetable.operator.common.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/mock")
@RequiredArgsConstructor
public class MockAuthController {

    private final AppProperties appProperties;

    @GetMapping("/login")
    public void mockLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "mock-google-id-12345");
        attributes.put("name", "Demo User (Mock)");
        attributes.put("email", appProperties.auth().defaultUserEmail());
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
        
        // 세션을 강제로 생성하여 유지합니다.
        request.getSession(true).setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

        response.sendRedirect(appProperties.frontendBaseUrl() + "/auth/callback?status=success&mock=true");
    }
}
