package ru.yandex.practicum.bionicpro.auth.report;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Обработчик инфраструктурных ошибок проксирования отчётов.
 */
@RestControllerAdvice
public class ReportsProxyExceptionHandler {

    @ExceptionHandler(ReportsServiceUnavailableException.class)
    public ProblemDetail handleReportsServiceUnavailable(
            ReportsServiceUnavailableException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getMessage()
        );

        problem.setTitle("Reports service is unavailable");
        problem.setType(
                URI.create(
                        "urn:bionicpro:reports:service-unavailable"
                )
        );

        return problem;
    }
}