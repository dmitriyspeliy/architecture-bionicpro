package ru.yandex.practicum.bionicpro.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.yandex.practicum.bionicpro.auth.profile.UserProfileService;

import java.time.Instant;

/**
 * Предоставляет frontend-приложению состояние серверной сессии
 * без раскрытия access token и refresh token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthSessionController {

    private final UserProfileService userProfileService;

    /** Идентификатор OAuth2-клиента из application.yml. */
    private static final String CLIENT_REGISTRATION_ID = "keycloak";

    /** Менеджер получения и обновления OAuth2-токенов. */
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    /**
     * Создаёт контроллер проверки сессии.
     *
     * @param authorizedClientManager менеджер OAuth2-клиентов
     */
    public AuthSessionController(
            UserProfileService userProfileService, OAuth2AuthorizedClientManager authorizedClientManager
    ) {
        this.userProfileService = userProfileService;
        this.authorizedClientManager = authorizedClientManager;
    }

    /**
     * Проверяет серверную сессию и при необходимости обновляет access token.
     *
     * @param authentication текущий пользователь
     * @param request        HTTP-запрос
     * @param response       HTTP-ответ
     * @return безопасные сведения о сессии
     */
    @GetMapping("/session")
    public AuthSessionResponse getSession(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        OAuth2AuthorizeRequest authorizeRequest =
                OAuth2AuthorizeRequest
                        .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                        .principal(authentication)
                        .attributes(attributes -> {
                            attributes.put(
                                    HttpServletRequest.class.getName(),
                                    request
                            );
                            attributes.put(
                                    HttpServletResponse.class.getName(),
                                    response
                            );
                        })
                        .build();

        OAuth2AuthorizedClient authorizedClient =
                authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "OAuth2 session is not authorized"
            );
        }

        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            userProfileService.upsert(oidcUser);
        }

        return new AuthSessionResponse(
                authentication.getName(),
                authorizedClient.getAccessToken().getExpiresAt()
        );
    }

    /**
     * Безопасное представление пользовательской сессии.
     *
     * @param username             идентификатор пользователя
     * @param accessTokenExpiresAt срок действия access token
     */
    public record AuthSessionResponse(
            String username,
            Instant accessTokenExpiresAt
    ) {
    }
}