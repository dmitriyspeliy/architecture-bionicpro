CREATE TABLE IF NOT EXISTS reports_olap.crm_customer_cdc_state
(
    customer_id        UUID,
    user_subject       String,
    email              String,
    full_name          String,
    region             Nullable(String),
    prosthesis_id      UUID,
    prosthesis_model   LowCardinality(String),
    status             LowCardinality(String),

    source_updated_at  DateTime64(6, 'UTC'),
    source_lsn         UInt64,
    source_operation   LowCardinality(String),

    kafka_partition    UInt64,
    kafka_offset       UInt64,

    is_deleted         UInt8,
    cdc_received_at    DateTime64(3, 'UTC')
)
    ENGINE = ReplacingMergeTree(
    source_lsn,
    is_deleted
)
ORDER BY customer_id;


CREATE TABLE IF NOT EXISTS reports_olap.crm_customer_kafka_raw
(
    message String
)
    ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list =
        'bionicpro-crm.public.crm_customer',
    kafka_group_name =
        'clickhouse-bionicpro-crm-cdc-v1',
    kafka_format = 'JSONAsString',
    kafka_num_consumers = 1,
    kafka_flush_interval_ms = 1000;


CREATE MATERIALIZED VIEW IF NOT EXISTS
    reports_olap.crm_customer_kafka_mv
            TO reports_olap.crm_customer_cdc_state
AS
SELECT
    toUUID(
            JSONExtractString(row_json, 'customer_id')
    ) AS customer_id,

    JSONExtractString(
            row_json,
            'user_subject'
    ) AS user_subject,

    JSONExtractString(
            row_json,
            'email'
    ) AS email,

    JSONExtractString(
            row_json,
            'full_name'
    ) AS full_name,

    nullIf(
            JSONExtractString(row_json, 'region'),
            ''
    ) AS region,

    toUUID(
            JSONExtractString(row_json, 'prosthesis_id')
    ) AS prosthesis_id,

    JSONExtractString(
            row_json,
            'prosthesis_model'
    ) AS prosthesis_model,

    JSONExtractString(
            row_json,
            'status'
    ) AS status,

    parseDateTime64BestEffort(
            JSONExtractString(
                    row_json,
                    'updated_at'
            ),
            6,
            'UTC'
    ) AS source_updated_at,

    source_lsn,
    operation AS source_operation,

    kafka_partition,
    kafka_offset,

    toUInt8(operation = 'd') AS is_deleted,
    now64(3, 'UTC') AS cdc_received_at

FROM
    (
        SELECT
            if(
                    operation = 'd',
                    JSONExtractRaw(message, 'before'),
                    JSONExtractRaw(message, 'after')
            ) AS row_json,

            operation,
            source_lsn,
            kafka_partition,
            kafka_offset

        FROM
            (
                SELECT
                    message,

                    JSONExtractString(
                            message,
                            'op'
                    ) AS operation,

                    JSONExtractUInt(
                            message,
                            'source',
                            'lsn'
                    ) AS source_lsn,

                    _partition AS kafka_partition,
                    _offset AS kafka_offset

                FROM reports_olap.crm_customer_kafka_raw
            )
    )
WHERE operation IN ('r', 'c', 'u', 'd')
  AND row_json != ''
  AND row_json != 'null';


CREATE VIEW IF NOT EXISTS
        reports_olap.crm_customer_current
AS
SELECT
    customer_id,
    user_subject,
    email,
    full_name,
    region,
    prosthesis_id,
    prosthesis_model,
    status,
    source_updated_at,
    source_lsn,
    source_operation,
    cdc_received_at
FROM reports_olap.crm_customer_cdc_state FINAL
WHERE is_deleted = 0;