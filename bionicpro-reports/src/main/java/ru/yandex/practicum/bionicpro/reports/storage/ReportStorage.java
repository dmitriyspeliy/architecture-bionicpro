package ru.yandex.practicum.bionicpro.reports.storage;

import java.time.Instant;
import java.util.Optional;

/**
 * Хранилище сформированных отчётов.
 */
public interface ReportStorage {

    /**
     * Находит метаданные объекта без скачивания его содержимого.
     */
    Optional<StoredReportMetadata> find(String objectKey);

    /**
     * Сохраняет сформированный JSON-отчёт.
     */
    void save(
            String objectKey,
            byte[] content,
            Instant generatedAt
    );
}