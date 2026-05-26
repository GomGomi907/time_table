package com.timetable.operator.auth.infrastructure;

import com.timetable.operator.common.config.AppProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private final AppProperties appProperties;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        String reason = firstText(request.getParameter("error"), exception.getClass().getSimpleName());
        String message = firstText(request.getParameter("error_description"), exception.getMessage());
        String callbackUrl = appProperties.frontendBaseUrl() + "/login/oauth2/code/google";
        String redirectUrl = UriComponentsBuilder
                .fromUriString(appProperties.frontendBaseUrl())
                .path("/auth/callback")
                .queryParam("status", "error")
                .queryParam("reason", reason)
                .queryParam("message", message)
                .queryParam("callbackUrl", callbackUrl)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        response.sendRedirect(redirectUrl);
    }

    private String firstText(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(fallback)) {
            return fallback;
        }
        return "oauth_error";
    }
}
