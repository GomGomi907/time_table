package com.timetable.operator.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
@RequiredArgsConstructor
@Conditional(GoogleOauthConfiguredCondition.class)
public class GoogleOAuthConfig {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository() {
        GoogleOauthCredentials credentials = GoogleOauthCredentialsSupport.resolve(
                appProperties.auth().googleClientId(),
                appProperties.auth().googleClientSecret(),
                appProperties.auth().googleCredentialsFile(),
                objectMapper
        );

        ClientRegistration googleRegistration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(credentials.clientId())
                .clientSecret(credentials.clientSecret())
                .scope(appProperties.googleOauthScopes())
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
        return new InMemoryClientRegistrationRepository(googleRegistration);
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository
    ) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }
}
