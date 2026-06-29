package ru.yandex.practicum.bionicpro.auth.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;

/**
 * Выполняет внутренние запросы BFF к сервису отчётов.
 */
@Component
public class ReportsProxyClient {

    private final RestClient reportsRestClient;

    public ReportsProxyClient(RestClient reportsRestClient) {
        this.reportsRestClient = reportsRestClient;
    }

    /**
     * Получает отчёт, передавая access token текущего пользователя.
     *
     * <p>Hop-by-hop заголовки внутреннего HTTP-ответа, например
     * {@code Transfer-Encoding}, наружу не передаются.</p>
     *
     * @param from        начало периода
     * @param to          конец периода
     * @param accessToken access token пользователя
     * @return очищенный ответ сервиса отчётов
     */
    public ResponseEntity<String> getReport(
            LocalDate from,
            LocalDate to,
            String accessToken
    ) {
        try {
            ResponseEntity<String> upstreamResponse =
                    reportsRestClient.get()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/reports")
                                    .queryParam("from", from)
                                    .queryParam("to", to)
                                    .build()
                            )
                            .headers(headers ->
                                    headers.setBearerAuth(accessToken)
                            )
                            .accept(
                                    MediaType.APPLICATION_JSON,
                                    MediaType.APPLICATION_PROBLEM_JSON
                            )
                            .retrieve()
                            .toEntity(String.class);

            return sanitizeResponse(upstreamResponse);

        } catch (RestClientResponseException exception) {
            return sanitizeErrorResponse(exception);

        } catch (ResourceAccessException exception) {
            throw new ReportsServiceUnavailableException(
                    "Reports service is unavailable",
                    exception
            );
        }
    }

    /**
     * Создаёт новый ответ без внутренних transport-заголовков.
     */
    private ResponseEntity<String> sanitizeResponse(
            ResponseEntity<String> upstreamResponse
    ) {
        HttpHeaders headers = new HttpHeaders();

        MediaType contentType =
                upstreamResponse.getHeaders().getContentType();

        if (contentType != null) {
            headers.setContentType(contentType);
        }

        return new ResponseEntity<>(
                upstreamResponse.getBody(),
                headers,
                upstreamResponse.getStatusCode()
        );
    }

    /**
     * Передаёт статус и тело ошибки без hop-by-hop заголовков.
     */
    private ResponseEntity<String> sanitizeErrorResponse(
            RestClientResponseException exception
    ) {
        HttpHeaders headers = new HttpHeaders();

        if (exception.getResponseHeaders() != null) {
            MediaType contentType =
                    exception.getResponseHeaders().getContentType();

            if (contentType != null) {
                headers.setContentType(contentType);
            }
        }

        return new ResponseEntity<>(
                exception.getResponseBodyAsString(),
                headers,
                exception.getStatusCode()
        );
    }
}