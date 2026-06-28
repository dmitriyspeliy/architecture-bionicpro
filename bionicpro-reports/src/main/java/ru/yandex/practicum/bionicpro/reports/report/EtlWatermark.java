package ru.yandex.practicum.bionicpro.reports.report;

import java.time.Instant;

/**
 * Состояние последнего запуска ETL.
 *
 * @param processedUntil верхняя граница обработанного интервала
 * @param status         статус ETL
 * @param updatedAt      время обновления watermark
 */
public record EtlWatermark(
        Instant processedUntil,
        String status,
        Instant updatedAt
) {
}