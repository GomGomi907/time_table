package com.timetable.operator.common.config;

import com.timetable.operator.auth.infrastructure.OAuthLoginSuccessHandler;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
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
    private final ObjectProvider<OAuthLoginSuccessHandler> oAuthLoginSuccessHandlerProvider;
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
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/auth/session", "/api/auth/google/start", "/actuator/health").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/api/auth/mock/login", "/api/auth/mock/callback").permitAll();
                    authorize.requestMatchers(HttpMethod.GET, "/oauth2/**", "/login/oauth2/**").permitAll();
                    if (h2ConsoleEnabled) {
                        authorize.requestMatchers("/h2-console/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .logout(logout -> logout.logoutUrl("/api/auth/logout"))
                .httpBasic(Customizer.withDefaults());

        if (appProperties.googleOauthEnabled()) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(oAuthLoginSuccessHandlerProvider.getObject()));
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
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
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
                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health")
        ));
        if (h2ConsoleEnabled) {
            requestMatchers.add(PathPatternRequestMatcher.withDefaults().matcher("/h2-console/**"));
        }
        return requestMatchers.toArray(RequestMatcher[]::new);
    }
}
