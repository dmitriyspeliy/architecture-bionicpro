CREATE TABLE IF NOT EXISTS reports_olap.user_report_daily
(
    user_subject            String,
    report_date             Date,

    customer_id             UUID,
    prosthesis_id           UUID,
    prosthesis_model        LowCardinality(String),
    region                  Nullable(String),

    telemetry_events        UInt64,
    usage_seconds           UInt64,

    average_battery_level   Nullable(Float64),
    minimum_battery_level   Nullable(Float64),
    average_temperature_c   Nullable(Float64),

    alerts_count            UInt64,
    last_telemetry_at       Nullable(DateTime64(3, 'UTC')),

    crm_updated_at          DateTime64(3, 'UTC'),
    etl_loaded_at           DateTime64(3, 'UTC')
)
    ENGINE = ReplacingMergeTree(etl_loaded_at)
PARTITION BY toYYYYMM(report_date)
ORDER BY (user_subject, report_date);