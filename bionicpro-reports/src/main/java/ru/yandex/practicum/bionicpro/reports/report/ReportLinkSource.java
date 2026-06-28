package ru.yandex.practicum.bionicpro.reports.report;

/**
 * Источник результата запроса отчёта.
 */
public enum ReportLinkSource {

    /**
     * Отчёт был сформирован и сохранён в S3.
     */
    GENERATED,

    /**
     * Готовый отчёт уже существовал в S3.
     */
    S3_HIT
}