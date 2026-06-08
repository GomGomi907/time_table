package com.timetable.operator.auth.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-controller-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.auth.mock-login-enabled=true",
        "app.encryption.key=test-auth-controller-encryption-key"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Test
    void csrfEndpointReturnsTokenAndCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void googleStartUsesSameOriginMockLoginPath() throws Exception {
        mockMvc.perform(get("/api/auth/google/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.url").value("/api/auth/mock/login"))
                .andExpect(jsonPath("$.message").value("Google Client Secret이 설정되지 않아 개발용 Mock 로그인으로 진행합니다."));
    }

    @Test
    void mockLoginAcceptsCustomIdentityForE2eIsolation() throws Exception {
        mockMvc.perform(get("/api/auth/mock/login")
                        .param("email", "e2e-user@time-table.test")
                        .param("name", "E2E User"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        "http://localhost:3000/auth/callback?status=success&mock=true"
                ))
                .andExpect(request().sessionAttribute("SPRING_SECURITY_CONTEXT", notNullValue()));
    }

    @Test
    void mockLoginCanSeedReadOnlyGoogleConnectionForE2e() throws Exception {
        mockMvc.perform(get("/api/auth/mock/login")
                        .param("email", "e2e-google-connected@time-table.test")
                        .param("name", "E2E Google Connected")
                        .param("connectGoogle", "true")
                        .param("writeCapable", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        "http://localhost:3000/auth/callback?status=success&mock=true"
                ))
                .andExpect(request().sessionAttribute("SPRING_SECURITY_CONTEXT", notNullValue()));

        assertThat(calendarConnectionRepository.findAll())
                .anySatisfy(connection -> {
                    assertThat(connection.getEmail()).isEqualTo("e2e-google-connected@time-table.test");
                    assertThat(connection.isCalendarReadEnabled()).isTrue();
                    assertThat(connection.isCalendarWriteEnabled()).isFalse();
                    assertThat(connection.getAccessToken()).isEqualTo("mock-google-access-token");
                });
    }
}
