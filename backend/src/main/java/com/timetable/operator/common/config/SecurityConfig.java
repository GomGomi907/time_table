package com.timetable.operator.common.config;

import com.timetable.operator.auth.infrastructure.OAuthLoginFailureHandler;
import com.timetable.operator.auth.infrastructure.OAuthLoginSuccessHandler;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppProperties appProperties;
    private final ObjectProvider<OAuthLoginFailureHandler> oAuthLoginFailureHandlerProvider;
    private final ObjectProvider<OAuthLoginSuccessHandler> oAuthLoginSuccessHandlerProvider;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;
    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(csrfIgnoredMatchers())
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exception -> exception.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")
                ))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    authorize.requestMatchers(
                            HttpMethod.GET,
                            "/api/auth/session",
                            "/api/auth/csrf",
                            "/api/auth/google/start",
                            "/actuator/health"
                    ).permitAll();
                    if (appProperties.mockLoginEnabled()) {
                        authorize.requestMatchers(HttpMethod.GET, "/api/auth/mock/login", "/api/auth/mock/callback").permitAll();
                    }
                    authorize.requestMatchers(HttpMethod.POST, "/api/sync/google/calendar/webhook").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/oauth2/**", "/login/oauth2/**").permitAll();
                    if (h2ConsoleEnabled) {
                        authorize.requestMatchers("/h2-console/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                // `/api/auth/logout` is an API endpoint implemented by AuthController.
                // Disable the default Spring Security logout filter so the controller
                // can return the stable no-content contract used by the frontend.
                .logout(logout -> logout.disable())
                .httpBasic(httpBasic -> httpBasic.disable());

        if (appProperties.googleOauthEnabled()) {
            http.oauth2Login(oauth2 -> {
                oauth2.failureHandler(oAuthLoginFailureHandlerProvider.getObject());
                oauth2.successHandler(oAuthLoginSuccessHandlerProvider.getObject());
                OAuth2AuthorizationRequestResolver authorizationRequestResolver =
                        googleOfflineAuthorizationRequestResolver();
                if (authorizationRequestResolver != null) {
                    oauth2.authorizationEndpoint(authorization -> authorization
                            .authorizationRequestResolver(authorizationRequestResolver));
                }
            });
        }

        if (h2ConsoleEnabled) {
            http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()));
        }
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(appProperties.frontendBaseUrl());
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-CSRF-TOKEN",
                "X-XSRF-TOKEN",
                "X-Requested-With",
                "Accept",
                "Origin"
        ));
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private RequestMatcher[] csrfIgnoredMatchers() {
        List<RequestMatcher> requestMatchers = new ArrayList<>(List.of(
                PathPatternRequestMatcher.withDefaults().matcher("/api/auth/logout"),
                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/login/oauth2/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/api/auth/mock/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health"),
                PathPatternRequestMatcher.withDefaults().matcher("/api/sync/google/calendar/webhook")
        ));
        if (h2ConsoleEnabled) {
            requestMatchers.add(PathPatternRequestMatcher.withDefaults().matcher("/h2-console/**"));
        }
        return requestMatchers.toArray(RequestMatcher[]::new);
    }

    private OAuth2AuthorizationRequestResolver googleOfflineAuthorizationRequestResolver() {
        ClientRegistrationRepository clientRegistrationRepository = clientRegistrationRepositoryProvider.getIfAvailable();
        if (clientRegistrationRepository == null) {
            return null;
        }
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(customizer -> customizer
                .additionalParameters(parameters -> {
                    parameters.put("access_type", "offline");
                    parameters.put("prompt", "consent");
                    parameters.put("include_granted_scopes", "true");
                }));
        return resolver;
    }
}
