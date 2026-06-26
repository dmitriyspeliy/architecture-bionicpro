package ru.yandex.practicum.bionicpro.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ru.yandex.practicum.bionicpro.auth.security.SessionRotationFilter;

/**
 * Конфигурация OAuth2-аутентификации, PKCE
 * и серверных пользовательских сессий.
 *
 * <p>Access token и refresh token хранятся на стороне backend-сервиса
 * в HTTP-сессии, вынесенной в Redis. Frontend получает только
 * защищённую сессионную cookie.</p>
 */
@Configuration
public class SecurityConfig {

    /** Базовый URL запуска Authorization Code Flow. */
    private static final String AUTHORIZATION_BASE_URI =
            "/oauth2/authorization";

    /** Builder для создания PathPattern-based request matcher. */
    private static final PathPatternRequestMatcher.Builder PATHS =
            PathPatternRequestMatcher.withDefaults();

    /** Запросы к служебному health endpoint. */
    private static final RequestMatcher HEALTH_REQUEST =
            PATHS.matcher("/actuator/health");

    /** Запросы к защищённому API. */
    private static final RequestMatcher API_REQUESTS =
            PATHS.matcher("/api/**");

    /** Запросы, необходимые для выполнения OAuth2 Flow. */
    private static final RequestMatcher OAUTH_REQUESTS =
            new OrRequestMatcher(
                    PATHS.matcher("/oauth2/**"),
                    PATHS.matcher("/login/**"),
                    PATHS.matcher("/error")
            );

    /** URL frontend-приложения после успешной аутентификации. */
    private final String frontendUrl;

    /**
     * Создаёт конфигурацию безопасности.
     *
     * @param frontendUrl URL frontend-приложения
     */
    public SecurityConfig(
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        this.frontendUrl = frontendUrl;
    }

    /**
     * Хранит OAuth2-клиента, access token и refresh token
     * внутри серверной HTTP-сессии.
     *
     * @return репозиторий OAuth2-клиентов
     */
    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * Разрешает доступ к health endpoint без аутентификации.
     *
     * @param http конфигурация Spring Security
     * @return цепочка безопасности health endpoint
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain healthSecurityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .securityMatcher(HEALTH_REQUEST)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll()
                )
                .requestCache(requestCache -> requestCache.disable());

        return http.build();
    }

    /**
     * Защищает backend API с помощью серверной сессии.
     *
     * <p>Неаутентифицированный запрос получает HTTP 401
     * без перенаправления в Keycloak.</p>
     *
     * @param http конфигурация Spring Security
     * @return цепочка безопасности API
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .securityMatcher(API_REQUESTS)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(
                                        HttpStatus.UNAUTHORIZED
                                )
                        )
                )
                .requestCache(requestCache -> requestCache.disable())
                .addFilterAfter(
                        new SessionRotationFilter(),
                        AuthorizationFilter.class
                );

        return http.build();
    }

    /**
     * Обрабатывает запуск OAuth2 Flow и callback от Keycloak.
     *
     * @param http                       конфигурация Spring Security
     * @param requestResolver            резолвер OAuth2-запросов с PKCE
     * @param authorizedClientRepository серверное хранилище токенов
     * @return OAuth2-цепочка безопасности
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizationRequestResolver requestResolver,
            OAuth2AuthorizedClientRepository authorizedClientRepository
    ) throws Exception {
        http
                .securityMatcher(OAUTH_REQUESTS)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(
                                        requestResolver
                                )
                        )
                        .authorizedClientRepository(
                                authorizedClientRepository
                        )
                        .successHandler(
                                (request, response, authentication) ->
                                        response.sendRedirect(frontendUrl)
                        )
                )
                .oauth2Client(oauth2 -> oauth2
                        .authorizedClientRepository(
                                authorizedClientRepository
                        )
                )
                .sessionManagement(session -> session
                        .sessionFixation(fixation ->
                                fixation.changeSessionId()
                        )
                );

        return http.build();
    }

    /**
     * Запрещает обращения ко всем неизвестным backend URL.
     *
     * @param http конфигурация Spring Security
     * @return резервная цепочка безопасности
     * @throws Exception при ошибке конфигурации
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain fallbackSecurityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(
                                        HttpStatus.FORBIDDEN
                                )
                        )
                )
                .requestCache(requestCache -> requestCache.disable());

        return http.build();
    }

    /**
     * Формирует OAuth2 Authorization Request с обязательным PKCE.
     *
     * @param clientRegistrationRepository конфигурации OAuth2-клиентов
     * @return резолвер OAuth2-запросов
     */
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository
    ) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        AUTHORIZATION_BASE_URI
                );

        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce()
        );

        return resolver;
    }

    /**
     * Управляет получением и автоматическим обновлением access token.
     *
     * @param clientRegistrationRepository конфигурации OAuth2-клиентов
     * @param authorizedClientRepository    серверное хранилище токенов
     * @return менеджер OAuth2-клиентов
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        OAuth2AuthorizedClientProvider provider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .build();

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository,
                        authorizedClientRepository
                );

        manager.setAuthorizedClientProvider(provider);

        return manager;
    }
}
