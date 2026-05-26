package com.timetable.operator.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.common.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.util.UriComponentsBuilder;

class OAuthLoginFailureHandlerTest {

    @Test
    void redirectsOauthFailuresToPublicFrontendCallback() throws Exception {
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(appProperties("https://timetable.example.test"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("error", "access_denied");
        request.setParameter("error_description", "User denied consent");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request,
                response,
                new AuthenticationException("invalid oauth callback") {
                }
        );

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://timetable.example.test/auth/callback?status=error&reason=access_denied&message=User%20denied%20consent&callbackUrl=https://timetable.example.test/login/oauth2/code/google");
    }

    @Test
    void redirectsOauthFailuresWithSafeFallbackDiagnostics() throws Exception {
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(appProperties("https://timetable.example.test"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new AuthenticationException("invalid state") {
                }
        );

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        var queryParams = UriComponentsBuilder
                .fromUriString(response.getRedirectedUrl())
                .build()
                .getQueryParams();
        assertThat(queryParams.getFirst("status")).isEqualTo("error");
        assertThat(queryParams.getFirst("reason")).isEqualTo("oauth_error");
        assertThat(queryParams.getFirst("message")).isEqualTo("invalid%20state");
        assertThat(queryParams.getFirst("callbackUrl"))
                .isEqualTo("https://timetable.example.test/login/oauth2/code/google");
    }

    private AppProperties appProperties(String frontendBaseUrl) {
        return new AppProperties(
                frontendBaseUrl,
                new AppProperties.AuthProperties(
                        "local@time-table.dev",
                        "Local User",
                        "client-id",
                        "client-secret",
                        "",
                        "https://oauth2.googleapis.com/token",
                        List.of("openid", "profile", "email"),
                        false
                ),
                new AppProperties.CalendarProperties(7, 30),
                new AppProperties.ScheduleProperties(10, 15, 30, null, null),
                new AppProperties.AiProperties(false, "", "", "", 768, 0.0, 8)
        );
    }
}
