package ru.yandex.practicum.bionicpro.auth.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * BFF API для получения пользовательских отчётов.
 *
 * <p>Frontend не получает access token. BFF извлекает токен
 * из серверной OAuth2-сессии и передаёт его reports-сервису.</p>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsProxyController {

    private static final String CLIENT_REGISTRATION_ID =
            "keycloak";

    private final ReportsProxyClient reportsProxyClient;

    public ReportsProxyController(
            ReportsProxyClient reportsProxyClient
    ) {
        this.reportsProxyClient = reportsProxyClient;
    }

    /**
     * Получает отчёт текущего пользователя.
     *
     * @param from             начало периода
     * @param to               конец периода
     * @param authorizedClient текущий OAuth2-клиент
     * @return ответ reports-сервиса
     */
    @GetMapping
    public ResponseEntity<String> getReport(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @RegisteredOAuth2AuthorizedClient(
                    CLIENT_REGISTRATION_ID
            )
            OAuth2AuthorizedClient authorizedClient
    ) {
        String accessToken = authorizedClient
                .getAccessToken()
                .getTokenValue();

        return reportsProxyClient.getReport(
                from,
                to,
                accessToken
        );
    }
}