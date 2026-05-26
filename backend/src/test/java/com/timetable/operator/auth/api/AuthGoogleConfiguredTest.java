package com.timetable.operator.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-google-configured-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.frontend-base-url=https://timetable.example.test",
        "app.auth.google-client-id=test-client-id",
        "app.auth.google-client-secret=test-client-secret",
        "app.auth.mock-login-enabled=false"
})
@AutoConfigureMockMvc
class AuthGoogleConfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void googleStartUsesSameOriginAuthorizationPathWhenOauthIsConfigured() throws Exception {
        mockMvc.perform(get("/api/auth/google/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.url").value("/oauth2/authorization/google"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }
}
