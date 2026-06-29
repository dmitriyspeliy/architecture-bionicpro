CREATE DATABASE IF NOT EXISTS reports_olap;

CREATE TABLE IF NOT EXISTS reports_olap.stg_crm_customer
(
    customer_id        UUID,
    user_subject       String,
    email              String,
    full_name          String,
    region             Nullable(String),
    prosthesis_id      UUID,
    prosthesis_model   LowCardinality(String),
    status             LowCardinality(String),
    source_updated_at  DateTime64(3, 'UTC'),
    etl_loaded_at      DateTime64(3, 'UTC')
)
    ENGINE = ReplacingMergeTree(etl_loaded_at)
ORDER BY (user_subject, customer_id);

CREATE TABLE IF NOT EXISTS reports_olap.stg_prosthesis_telemetry
(
    event_id            UUID,
    prosthesis_id       UUID,
    event_time          DateTime64(3, 'UTC'),
    usage_seconds       UInt32,
    battery_level       Nullable(Float32),
    temperature_c       Nullable(Float32),
    alert_code          Nullable(String),
    source_inserted_at  DateTime64(3, 'UTC'),
    etl_loaded_at       DateTime64(3, 'UTC')
)
    ENGINE = ReplacingMergeTree(etl_loaded_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (prosthesis_id, event_time, event_id);