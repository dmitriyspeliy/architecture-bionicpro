CREATE TABLE IF NOT EXISTS reports_olap.etl_watermark
(
    pipeline_name     LowCardinality(String),
    processed_until   DateTime64(3, 'UTC'),
    status            LowCardinality(String),
    updated_at        DateTime64(3, 'UTC')
)
    ENGINE = ReplacingMergeTree(updated_at)
ORDER BY pipeline_name;

INSERT INTO reports_olap.etl_watermark
(
    pipeline_name,
    processed_until,
    status,
    updated_at
)
VALUES
    (
        'bionicpro_reports_etl',
        toDateTime64('1970-01-01 00:00:00', 3, 'UTC'),
        'NEVER_RUN',
        now64(3, 'UTC')
    );