package ru.yandex.practicum.bionicpro.reports.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Настройка сервиса как stateless OAuth2 Resource Server.
 */
@Configuration
public class SecurityConfig {

    /**
     * Разрешает health-check без авторизации.
     * Все API отчётов требуют действительный JWT.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        PathPatternRequestMatcher healthMatcher =
                PathPatternRequestMatcher.withDefaults()
                        .matcher("/actuator/health");

        PathPatternRequestMatcher reportsExactMatcher =
                PathPatternRequestMatcher.withDefaults()
                        .matcher("/reports");

        PathPatternRequestMatcher reportsNestedMatcher =
                PathPatternRequestMatcher.withDefaults()
                        .matcher("/reports/**");

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(healthMatcher).permitAll()
                        .requestMatchers(
                                reportsExactMatcher,
                                reportsNestedMatcher
                        ).authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.jwt(Customizer.withDefaults())
                );

        return http.build();
    }
}