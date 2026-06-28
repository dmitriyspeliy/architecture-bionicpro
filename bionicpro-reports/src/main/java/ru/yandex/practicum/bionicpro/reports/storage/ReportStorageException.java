package ru.yandex.practicum.bionicpro.reports.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Ошибка взаимодействия с объектным хранилищем.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ReportStorageException extends RuntimeException {

    public ReportStorageException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}