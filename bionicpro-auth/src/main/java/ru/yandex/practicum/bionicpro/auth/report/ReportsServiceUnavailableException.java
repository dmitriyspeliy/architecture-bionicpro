package ru.yandex.practicum.bionicpro.auth.report;

/**
 * Сервис отчётов недоступен по сети.
 */
public class ReportsServiceUnavailableException
        extends RuntimeException {

    public ReportsServiceUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}