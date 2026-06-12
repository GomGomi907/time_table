package com.timetable.operator.auth.api;

import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timetable.operator.auth.infrastructure.AppUserRepository;
import com.timetable.operator.calendar.infrastructure.CalendarConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void csrfEndpointReturnsTokenContract() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty());
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

    @Test
    void disconnectGoogleClearsStoredTokensAndCapabilities() throws Exception {
        MockHttpSession session = loginWithMockGoogleConnection(
                "disconnect-google@time-table.test",
                "Disconnect Google"
        );

        mockMvc.perform(post("/api/auth/google/disconnect")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleConnectionStatus").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.calendarWriteEnabled").value(false))
                .andExpect(jsonPath("$.tasksWriteEnabled").value(false));

        assertThat(calendarConnectionRepository.findAll())
                .filteredOn(connection -> "disconnect-google@time-table.test".equals(connection.getEmail()))
                .singleElement()
                .satisfies(connection -> {
                    assertThat(connection.getStatus().name()).isEqualTo("NOT_CONNECTED");
                    assertThat(connection.getAccessToken()).isNull();
                    assertThat(connection.getRefreshToken()).isNull();
                    assertThat(connection.getGrantedScopes()).isEmpty();
                    assertThat(connection.isCalendarReadEnabled()).isFalse();
                    assertThat(connection.isCalendarWriteEnabled()).isFalse();
                    assertThat(connection.isTasksReadEnabled()).isFalse();
                    assertThat(connection.isTasksWriteEnabled()).isFalse();
                });
    }

    @Test
    void deleteAccountRemovesUserAndCascadedGoogleConnection() throws Exception {
        MockHttpSession session = loginWithMockGoogleConnection(
                "delete-account@time-table.test",
                "Delete Account"
        );

        assertThat(appUserRepository.findByEmail("delete-account@time-table.test")).isPresent();

        mockMvc.perform(post("/api/auth/account/delete")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Clear-Site-Data", "\"cookies\""));

        assertThat(appUserRepository.findByEmail("delete-account@time-table.test")).isEmpty();
        assertThat(calendarConnectionRepository.findAll())
                .noneSatisfy(connection -> assertThat(connection.getEmail()).isEqualTo("delete-account@time-table.test"));
    }

    private MockHttpSession loginWithMockGoogleConnection(String email, String name) throws Exception {
        MvcResult loginResult = mockMvc.perform(get("/api/auth/mock/login")
                        .param("email", email)
                        .param("name", name)
                        .param("connectGoogle", "true")
                        .param("writeCapable", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(request().sessionAttribute("SPRING_SECURITY_CONTEXT", notNullValue()))
                .andReturn();
        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }
}
