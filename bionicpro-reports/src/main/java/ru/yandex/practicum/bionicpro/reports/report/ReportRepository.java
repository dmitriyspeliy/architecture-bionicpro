package ru.yandex.practicum.bionicpro.reports.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Читает готовые отчёты и состояние ETL из ClickHouse.
 */
@Repository
public class ReportRepository {

    private static final String PIPELINE_NAME =
            "bionicpro_reports_etl";

    private static final String WATERMARK_SQL = """
        SELECT
            argMax(
                processed_until,
                updated_at
            ) AS latest_processed_until,
            argMax(
                status,
                updated_at
            ) AS latest_status,
            max(updated_at) AS latest_updated_at
        FROM reports_olap.etl_watermark
        WHERE pipeline_name = ?
        """;

    private static final String REPORT_SQL = """
            SELECT
                report_date,
                customer_id,
                prosthesis_id,
                prosthesis_model,
                region,
                telemetry_events,
                usage_seconds,
                average_battery_level,
                minimum_battery_level,
                average_temperature_c,
                alerts_count,
                last_telemetry_at
            FROM reports_olap.user_report_daily FINAL
            WHERE user_subject = ?
              AND report_date >= toDate(?)
              AND report_date <= toDate(?)
            ORDER BY report_date
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Возвращает состояние последней загрузки отчётной витрины.
     */
    public Optional<EtlWatermark> findLatestWatermark() {
        List<EtlWatermark> rows = jdbcTemplate.query(
                WATERMARK_SQL,
                (resultSet, rowNumber) -> {
                    Timestamp processedUntil =
                            resultSet.getTimestamp(
                                    "latest_processed_until"
                            );

                    String status =
                            resultSet.getString("latest_status");

                    Timestamp updatedAt =
                            resultSet.getTimestamp(
                                    "latest_updated_at"
                            );

                    if (
                            processedUntil == null
                                    || status == null
                                    || updatedAt == null
                    ) {
                        return null;
                    }

                    return new EtlWatermark(
                            processedUntil.toInstant(),
                            status,
                            updatedAt.toInstant()
                    );
                },
                PIPELINE_NAME
        );

        return rows.stream()
                .filter(row -> row != null)
                .findFirst();
    }

    /**
     * Возвращает готовые дневные агрегаты только для заданного subject.
     */
    public List<DailyReport> findReports(
            String userSubject,
            LocalDate from,
            LocalDate to
    ) {
        return jdbcTemplate.query(
                REPORT_SQL,
                this::mapDailyReport,
                userSubject,
                from.toString(),
                to.toString()
        );
    }

    private DailyReport mapDailyReport(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        Timestamp lastTelemetryAt =
                resultSet.getTimestamp("last_telemetry_at");

        return new DailyReport(
                resultSet.getDate("report_date").toLocalDate(),
                UUID.fromString(
                        resultSet.getString("customer_id")
                ),
                UUID.fromString(
                        resultSet.getString("prosthesis_id")
                ),
                resultSet.getString("prosthesis_model"),
                resultSet.getString("region"),
                resultSet.getLong("telemetry_events"),
                resultSet.getLong("usage_seconds"),
                nullableDouble(
                        resultSet,
                        "average_battery_level"
                ),
                nullableDouble(
                        resultSet,
                        "minimum_battery_level"
                ),
                nullableDouble(
                        resultSet,
                        "average_temperature_c"
                ),
                resultSet.getLong("alerts_count"),
                lastTelemetryAt == null
                        ? null
                        : lastTelemetryAt.toInstant()
        );
    }

    private Double nullableDouble(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        double value = resultSet.getDouble(column);

        return resultSet.wasNull() ? null : value;
    }
}