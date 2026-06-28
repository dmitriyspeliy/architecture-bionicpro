package ru.yandex.practicum.bionicpro.reports.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Подготовленный дневной отчёт из OLAP-витрины.
 *
 * @param reportDate             дата отчёта в UTC
 * @param customerId             идентификатор клиента CRM
 * @param prosthesisId           идентификатор протеза
 * @param prosthesisModel        модель протеза
 * @param region                 регион клиента
 * @param telemetryEvents        количество событий телеметрии
 * @param usageSeconds           длительность использования в секундах
 * @param averageBatteryLevel    средний уровень заряда
 * @param minimumBatteryLevel    минимальный уровень заряда
 * @param averageTemperatureC    средняя температура
 * @param alertsCount            количество предупреждений
 * @param lastTelemetryAt        время последнего события
 */
public record DailyReport(
        LocalDate reportDate,
        UUID customerId,
        UUID prosthesisId,
        String prosthesisModel,
        String region,
        long telemetryEvents,
        long usageSeconds,
        Double averageBatteryLevel,
        Double minimumBatteryLevel,
        Double averageTemperatureC,
        long alertsCount,
        Instant lastTelemetryAt
) {
}