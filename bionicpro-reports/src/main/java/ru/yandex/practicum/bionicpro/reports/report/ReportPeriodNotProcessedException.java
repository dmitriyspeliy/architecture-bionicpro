package ru.yandex.practicum.bionicpro.reports.report;

import java.time.LocalDate;

public class ReportPeriodNotProcessedException
        extends RuntimeException {

    private final LocalDate requestedTo;
    private final LocalDate availableThrough;

    public ReportPeriodNotProcessedException(
            LocalDate requestedTo,
            LocalDate availableThrough
    ) {
        super(
                "Requested report period has not been completely "
                        + "processed by ETL"
        );

        this.requestedTo = requestedTo;
        this.availableThrough = availableThrough;
    }

    public LocalDate getRequestedTo() {
        return requestedTo;
    }

    public LocalDate getAvailableThrough() {
        return availableThrough;
    }
}