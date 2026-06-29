package ru.yandex.practicum.bionicpro.reports.report;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Преобразует ошибки отчётности в стандартный HTTP Problem Detail.
 */
@RestControllerAdvice
public class ReportExceptionHandler {

    @ExceptionHandler(InvalidReportPeriodException.class)
    public ProblemDetail handleInvalidPeriod(
            InvalidReportPeriodException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );

        problem.setTitle("Invalid report period");
        problem.setType(
                URI.create("urn:bionicpro:report:invalid-period")
        );

        return problem;
    }

    @ExceptionHandler(ReportPeriodNotProcessedException.class)
    public ProblemDetail handleNotProcessedPeriod(
            ReportPeriodNotProcessedException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problem.setTitle("Report period is not processed");
        problem.setType(
                URI.create("urn:bionicpro:report:period-not-processed")
        );
        problem.setProperty(
                "requestedTo",
                exception.getRequestedTo()
        );
        problem.setProperty(
                "availableThrough",
                exception.getAvailableThrough()
        );

        return problem;
    }

    @ExceptionHandler(ReportDataUnavailableException.class)
    public ProblemDetail handleUnavailableData(
            ReportDataUnavailableException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getMessage()
        );

        problem.setTitle("Report data is unavailable");
        problem.setType(
                URI.create("urn:bionicpro:report:data-unavailable")
        );

        return problem;
    }
}