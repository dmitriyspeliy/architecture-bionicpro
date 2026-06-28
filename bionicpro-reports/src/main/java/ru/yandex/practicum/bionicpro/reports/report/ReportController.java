package ru.yandex.practicum.bionicpro.reports.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * API пользовательских отчётов.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Возвращает отчёт только для текущего пользователя.
     *
     * <p>Идентификатор пользователя берётся из JWT claim {@code sub}.
     * Передать идентификатор другого пользователя через API нельзя.</p>
     */
    @GetMapping
    public ReportLinkResponse getReport(
            @AuthenticationPrincipal Jwt jwt,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to
    ) {
        return reportService.getReport(
                jwt.getSubject(),
                from,
                to
        );
    }
}