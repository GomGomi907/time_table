package com.timetable.operator.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.timetable.operator.common.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;

class OAuthLoginFailureHandlerTest {

    @Test
    void redirectsOauthFailuresToPublicFrontendCallback() throws Exception {
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(appProperties("https://timetable.example.test"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new AuthenticationException("invalid oauth callback") {
                }
        );

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://timetable.example.test/auth/callback?status=error");
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
