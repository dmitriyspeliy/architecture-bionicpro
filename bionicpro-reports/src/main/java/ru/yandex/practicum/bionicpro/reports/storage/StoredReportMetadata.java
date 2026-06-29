package ru.yandex.practicum.bionicpro.reports.storage;

import java.time.Instant;

/**
 * Метаданные уже сохранённого отчёта.
 *
 * @param generatedAt время формирования отчёта
 */
public record StoredReportMetadata(
        Instant generatedAt
) {
}