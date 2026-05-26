package com.timetable.operator.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void googleStartUsesSameOriginAuthorizationPathWhenOauthIsConfigured() throws Exception {
        mockMvc.perform(get("/api/auth/google/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.url").value("/oauth2/authorization/google"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void protectedApiReturnsUnauthorizedInsteadOfInternalOauthRedirect() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void googleTokenExchangeUsesClientSecretPostAuthentication() {
        var repository = (InMemoryClientRegistrationRepository) clientRegistrationRepository;
        var googleRegistration = repository.findByRegistrationId("google");

        org.assertj.core.api.Assertions.assertThat(googleRegistration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        org.assertj.core.api.Assertions.assertThat(googleRegistration.getRedirectUri())
                .isEqualTo("https://timetable.example.test/login/oauth2/code/{registrationId}");
    }
}
