DROP VIEW IF EXISTS
    reports_olap.user_report_daily_cdc_mv;

ALTER TABLE reports_olap.user_report_daily_cdc
    ADD COLUMN IF NOT EXISTS
        crm_source_lsn UInt64
    AFTER crm_updated_at;


CREATE MATERIALIZED VIEW
    reports_olap.user_report_daily_cdc_mv
REFRESH EVERY 30 SECOND
TO reports_olap.user_report_daily_cdc
AS
SELECT
    crm.user_subject AS user_subject,

    toDate(telemetry.event_time)
                     AS report_date,

    any(crm.customer_id)
       AS customer_id,

    any(crm.prosthesis_id)
        AS prosthesis_id,

    any(crm.prosthesis_model)
        AS prosthesis_model,

    any(crm.region)
        AS region,

    count()
        AS telemetry_events,

    sum(
        toUInt64(telemetry.usage_seconds)
    ) AS usage_seconds,

    avg(telemetry.battery_level)
        AS average_battery_level,

    min(telemetry.battery_level)
        AS minimum_battery_level,

    avg(telemetry.temperature_c)
        AS average_temperature_c,

    countIf(
        ifNull(telemetry.alert_code, '') != ''
    ) AS alerts_count,

    max(telemetry.event_time)
        AS last_telemetry_at,

    max(crm.source_updated_at)
        AS crm_updated_at,

    max(crm.source_lsn)
        AS crm_source_lsn,

    now64(3, 'UTC')
        AS etl_loaded_at

FROM
(
    SELECT
        event_id,
        prosthesis_id,
        event_time,
        usage_seconds,
        battery_level,
        temperature_c,
        alert_code
    FROM reports_olap.stg_prosthesis_telemetry FINAL
) AS telemetry

INNER JOIN
(
    SELECT
        customer_id,
        user_subject,
        region,
        prosthesis_id,
        prosthesis_model,
        source_updated_at,
        source_lsn
    FROM reports_olap.crm_customer_cdc_state FINAL
    WHERE is_deleted = 0
      AND status = 'ACTIVE'
) AS crm
    ON crm.prosthesis_id =
       telemetry.prosthesis_id

GROUP BY
    crm.user_subject,
    report_date;