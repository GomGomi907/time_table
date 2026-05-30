package com.timetable.operator.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.emptyString;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-production-guard-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.auth.mock-login-enabled=false"
})
@AutoConfigureMockMvc
class AuthProductionGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void googleStartDoesNotFallBackToMockLoginWhenMockLoginIsDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/google/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.message").value("Google OAuth 자격 증명이 설정되지 않았고 개발용 Mock 로그인도 비활성화되어 있습니다."));
    }

    @Test
    void mockLoginEndpointIsNotPublicWhenMockLoginIsDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/mock/login"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionDoesNotExposeLocalIdentityWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId", emptyString()))
                .andExpect(jsonPath("$.email", emptyString()))
                .andExpect(jsonPath("$.displayName", emptyString()))
                .andExpect(jsonPath("$.googleConnectionStatus").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.googleCapabilityStatus").value("not_connected"));
    }
}
