package ru.yandex.practicum.bionicpro.reports.report;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ссылка на сформированный пользовательский отчёт.
 *
 * @param reportUrl           CDN URL отчёта
 * @param source              источник результата
 * @param requestedFrom       начало периода
 * @param requestedTo         конец периода
 * @param dataAvailableThrough последний обработанный день
 * @param generatedAt         время формирования файла
 */
public record ReportLinkResponse(
        String reportUrl,
        ReportLinkSource source,
        LocalDate requestedFrom,
        LocalDate requestedTo,
        LocalDate dataAvailableThrough,
        Instant generatedAt
) {
}