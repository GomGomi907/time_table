package com.timetable.operator.auth.infrastructure;

import com.timetable.operator.auth.application.AuthService;
import com.timetable.operator.auth.domain.AppUser;
import com.timetable.operator.common.config.AppProperties;
import com.timetable.operator.common.security.CurrentUserProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientServiceProvider;
    private final CurrentUserProvider currentUserProvider;
    private final AuthService authService;
    private final AppProperties appProperties;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        AppUser user = currentUserProvider.getCurrentUser();
        OAuth2AuthorizedClient authorizedClient = null;
        if (authentication instanceof OAuth2AuthenticationToken token) {
            OAuth2AuthorizedClientService authorizedClientService = authorizedClientServiceProvider.getIfAvailable();
            if (authorizedClientService != null) {
                authorizedClient = authorizedClientService.loadAuthorizedClient(
                        token.getAuthorizedClientRegistrationId(),
                        token.getName()
                );
            }
        }

        authService.handleOauthLogin(user, authorizedClient);
        response.sendRedirect(appProperties.frontendBaseUrl() + "/auth/callback?status=success");
    }
}
