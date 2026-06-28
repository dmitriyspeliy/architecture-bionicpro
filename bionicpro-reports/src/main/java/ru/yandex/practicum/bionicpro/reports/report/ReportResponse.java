package ru.yandex.practicum.bionicpro.reports.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Отчёт авторизованного пользователя.
 *
 * @param userSubject         subject из JWT
 * @param requestedFrom       начало запрошенного периода
 * @param requestedTo         конец запрошенного периода
 * @param dataAvailableThrough последний полностью обработанный день
 * @param generatedAt         время формирования ответа
 * @param days                готовые дневные данные из OLAP
 */
public record ReportResponse(
        String userSubject,
        LocalDate requestedFrom,
        LocalDate requestedTo,
        LocalDate dataAvailableThrough,
        Instant generatedAt,
        List<DailyReport> days
) {
}