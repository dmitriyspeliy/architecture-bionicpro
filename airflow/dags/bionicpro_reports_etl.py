from __future__ import annotations

import logging
import os
from datetime import datetime, timedelta, timezone

import clickhouse_connect
import pendulum
import psycopg
from airflow.sdk import DAG, get_current_context, task
from airflow.timetables.interval import CronDataIntervalTimetable
from psycopg.rows import dict_row


LOGGER = logging.getLogger(__name__)

PIPELINE_NAME = "bionicpro_reports_etl"
INITIAL_LOOKBACK_DAYS = 30


def required_env(name: str) -> str:
    """Возвращает обязательную переменную окружения."""
    value = os.getenv(name)

    if value is None or value.strip() == "":
        raise RuntimeError(
            f"Required environment variable is not configured: {name}"
        )

    return value


def postgres_connection(prefix: str):
    """
    Создаёт соединение с PostgreSQL-источником.

    Поддерживаемые префиксы:
    - CRM_DB
    - TELEMETRY_DB
    """
    return psycopg.connect(
        host=required_env(f"{prefix}_HOST"),
        port=int(required_env(f"{prefix}_PORT")),
        dbname=required_env(f"{prefix}_NAME"),
        user=required_env(f"{prefix}_USERNAME"),
        password=required_env(f"{prefix}_PASSWORD"),
        row_factory=dict_row,
    )


def clickhouse_client():
    """Создаёт HTTP-клиент ClickHouse."""
    return clickhouse_connect.get_client(
        host=required_env("CLICKHOUSE_HOST"),
        port=int(required_env("CLICKHOUSE_PORT")),
        database=required_env("CLICKHOUSE_DATABASE"),
        username=required_env("CLICKHOUSE_USERNAME"),
        password=required_env("CLICKHOUSE_PASSWORD"),
    )


def as_utc(value: datetime) -> datetime:
    """Нормализует datetime в UTC."""
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)

    return value.astimezone(timezone.utc)


def parse_iso_datetime(value: str) -> datetime:
    """Преобразует ISO-строку из XCom в timezone-aware datetime."""
    return as_utc(datetime.fromisoformat(value))


def clickhouse_datetime(value: datetime) -> str:
    """Форматирует datetime для литерала DateTime64(3, 'UTC')."""
    return as_utc(value).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


@task(
    task_id="resolve_processing_window",
    retries=2,
    retry_delay=timedelta(seconds=15),
)
def resolve_processing_window() -> dict[str, str]:
    """
    Определяет интервал данных для текущего запуска.

    Первый запуск:
        data_interval_end - 30 дней -> data_interval_end.

    Последующие запуски:
        последний успешный processed_until -> data_interval_end.

    Если Airflow был остановлен, один новый запуск обработает весь
    пропущенный диапазон от watermark до текущего полного часа.
    """
    context = get_current_context()

    scheduled_start_value = context.get("data_interval_start")
    scheduled_end_value = context.get("data_interval_end")

    if scheduled_start_value is None or scheduled_end_value is None:
        # У ручного запуска Airflow 3 logical_date и data interval
        # могут отсутствовать. В таком случае используем последний
        # полностью завершённый час.
        scheduled_end = datetime.now(timezone.utc).replace(
            minute=0,
            second=0,
            microsecond=0,
        )
        scheduled_start = scheduled_end - timedelta(hours=1)

        LOGGER.info(
            "Manual DAG run without data interval. "
            "Using the last completed hour: start=%s, end=%s",
            scheduled_start.isoformat(),
            scheduled_end.isoformat(),
        )
    else:
        scheduled_start = as_utc(scheduled_start_value)
        scheduled_end = as_utc(scheduled_end_value)

    with clickhouse_client() as client:
        result = client.query(
            """
            SELECT
                argMax(processed_until, updated_at) AS processed_until,
                argMax(status, updated_at) AS status
            FROM reports_olap.etl_watermark
            WHERE pipeline_name = {pipeline_name:String}
            """,
            parameters={
                "pipeline_name": PIPELINE_NAME,
            },
        )

    rows = result.result_rows

    if not rows or rows[0][0] is None:
        processing_start = (
            scheduled_end - timedelta(days=INITIAL_LOOKBACK_DAYS)
        )
        previous_status = "NOT_FOUND"
    else:
        processed_until = as_utc(rows[0][0])
        previous_status = str(rows[0][1])

        if previous_status == "NEVER_RUN" or processed_until.year <= 1970:
            processing_start = (
                scheduled_end - timedelta(days=INITIAL_LOOKBACK_DAYS)
            )
        elif processed_until >= scheduled_end:
            # Интервал уже обрабатывался. Повторно пересчитываем
            # текущий scheduled interval идемпотентно.
            processing_start = scheduled_start
        else:
            processing_start = processed_until

    if processing_start >= scheduled_end:
        raise RuntimeError(
            "Invalid processing interval: "
            f"{processing_start.isoformat()} >= "
            f"{scheduled_end.isoformat()}"
        )

    window = {
        "start": processing_start.isoformat(),
        "end": scheduled_end.isoformat(),
    }

    LOGGER.info(
        "Resolved ETL window: start=%s, end=%s, previous_status=%s",
        window["start"],
        window["end"],
        previous_status,
    )

    return window


@task(
    task_id="load_crm_snapshot",
    retries=3,
    retry_delay=timedelta(seconds=20),
)
def load_crm_snapshot() -> int:
    """
    Полностью обновляет snapshot клиентов из CRM в ClickHouse.

    В snapshot хранится актуальная связь:
        user_subject -> customer -> prosthesis.
    """
    with postgres_connection("CRM_DB") as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                    customer_id,
                    user_subject,
                    email,
                    full_name,
                    region,
                    prosthesis_id,
                    prosthesis_model,
                    status,
                    updated_at
                FROM crm_customer
                """
            )
            source_rows = cursor.fetchall()

    loaded_at = datetime.now(timezone.utc)

    clickhouse_rows = [
        [
            row["customer_id"],
            row["user_subject"],
            row["email"],
            row["full_name"],
            row["region"],
            row["prosthesis_id"],
            row["prosthesis_model"],
            row["status"],
            row["updated_at"],
            loaded_at,
        ]
        for row in source_rows
    ]

    with clickhouse_client() as client:
        # CRM staging является полным актуальным snapshot.
        client.command(
            "TRUNCATE TABLE reports_olap.stg_crm_customer"
        )

        if clickhouse_rows:
            client.insert(
                "reports_olap.stg_crm_customer",
                clickhouse_rows,
                column_names=[
                    "customer_id",
                    "user_subject",
                    "email",
                    "full_name",
                    "region",
                    "prosthesis_id",
                    "prosthesis_model",
                    "status",
                    "source_updated_at",
                    "etl_loaded_at",
                ],
            )

    LOGGER.info(
        "CRM snapshot loaded: rows=%s",
        len(clickhouse_rows),
    )

    return len(clickhouse_rows)


@task(
    task_id="load_telemetry_interval",
    retries=3,
    retry_delay=timedelta(seconds=20),
)
def load_telemetry_interval(window: dict[str, str]) -> int:
    """
    Загружает телеметрию за полуинтервал [start, end).

    Перед вставкой данные этого же диапазона удаляются из staging,
    поэтому повторный запуск не создаёт дубликаты.
    """
    interval_start = parse_iso_datetime(window["start"])
    interval_end = parse_iso_datetime(window["end"])

    with postgres_connection("TELEMETRY_DB") as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                    event_id,
                    prosthesis_id,
                    event_time,
                    usage_seconds,
                    battery_level,
                    temperature_c,
                    alert_code,
                    inserted_at
                FROM prosthesis_telemetry
                WHERE event_time >= %s
                  AND event_time < %s
                ORDER BY event_time, event_id
                """,
                (
                    interval_start,
                    interval_end,
                ),
            )
            source_rows = cursor.fetchall()

    loaded_at = datetime.now(timezone.utc)

    clickhouse_rows = [
        [
            row["event_id"],
            row["prosthesis_id"],
            row["event_time"],
            row["usage_seconds"],
            (
                float(row["battery_level"])
                if row["battery_level"] is not None
                else None
            ),
            (
                float(row["temperature_c"])
                if row["temperature_c"] is not None
                else None
            ),
            row["alert_code"],
            row["inserted_at"],
            loaded_at,
        ]
        for row in source_rows
    ]

    start_sql = clickhouse_datetime(interval_start)
    end_sql = clickhouse_datetime(interval_end)

    with clickhouse_client() as client:
        client.command(
            f"""
            ALTER TABLE reports_olap.stg_prosthesis_telemetry
            DELETE WHERE
                event_time >= toDateTime64(
                    '{start_sql}',
                    3,
                    'UTC'
                )
                AND event_time < toDateTime64(
                    '{end_sql}',
                    3,
                    'UTC'
                )
            SETTINGS mutations_sync = 1
            """
        )

        if clickhouse_rows:
            client.insert(
                "reports_olap.stg_prosthesis_telemetry",
                clickhouse_rows,
                column_names=[
                    "event_id",
                    "prosthesis_id",
                    "event_time",
                    "usage_seconds",
                    "battery_level",
                    "temperature_c",
                    "alert_code",
                    "source_inserted_at",
                    "etl_loaded_at",
                ],
            )

    LOGGER.info(
        "Telemetry interval loaded: start=%s, end=%s, rows=%s",
        interval_start.isoformat(),
        interval_end.isoformat(),
        len(clickhouse_rows),
    )

    return len(clickhouse_rows)


@task(
    task_id="build_user_report_mart",
    retries=2,
    retry_delay=timedelta(seconds=20),
)
def build_user_report_mart(
    window: dict[str, str],
    crm_rows: int,
    telemetry_rows: int,
) -> int:
    """
    Пересчитывает дневную витрину за затронутые календарные дни.

    Даже при почасовом ETL пересчитывается полный календарный день.
    Поэтому строка витрины содержит накопленный дневной результат,
    а не только показатели последнего часа.
    """
    interval_start = parse_iso_datetime(window["start"])
    interval_end = parse_iso_datetime(window["end"])

    first_date = interval_start.date()
    last_date = (
        interval_end - timedelta(microseconds=1)
    ).date()

    mart_range_start = datetime.combine(
        first_date,
        datetime.min.time(),
        tzinfo=timezone.utc,
    )

    mart_range_end = datetime.combine(
        last_date + timedelta(days=1),
        datetime.min.time(),
        tzinfo=timezone.utc,
    )

    start_sql = clickhouse_datetime(mart_range_start)
    end_sql = clickhouse_datetime(mart_range_end)

    with clickhouse_client() as client:
        client.command(
            f"""
            ALTER TABLE reports_olap.user_report_daily
            DELETE WHERE
                report_date >= toDate('{first_date.isoformat()}')
                AND report_date <= toDate('{last_date.isoformat()}')
            SETTINGS mutations_sync = 1
            """
        )

        client.command(
            f"""
            INSERT INTO reports_olap.user_report_daily
            (
                user_subject,
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
                last_telemetry_at,
                crm_updated_at,
                etl_loaded_at
            )
            SELECT
                crm.user_subject,
                toDate(telemetry.event_time) AS report_date,

                any(crm.customer_id) AS customer_id,
                any(crm.prosthesis_id) AS prosthesis_id,
                any(crm.prosthesis_model) AS prosthesis_model,
                any(crm.region) AS region,

                count() AS telemetry_events,
                sum(toUInt64(telemetry.usage_seconds))
                    AS usage_seconds,

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

                now64(3, 'UTC')
                    AS etl_loaded_at

            FROM reports_olap.stg_prosthesis_telemetry AS telemetry

            INNER JOIN reports_olap.stg_crm_customer AS crm
                ON crm.prosthesis_id = telemetry.prosthesis_id

            WHERE crm.status = 'ACTIVE'
              AND telemetry.event_time >= toDateTime64(
                    '{start_sql}',
                    3,
                    'UTC'
              )
              AND telemetry.event_time < toDateTime64(
                    '{end_sql}',
                    3,
                    'UTC'
              )

            GROUP BY
                crm.user_subject,
                report_date
            """
        )

        count_result = client.query(
            f"""
            SELECT count()
            FROM reports_olap.user_report_daily
            WHERE report_date >= toDate('{first_date.isoformat()}')
              AND report_date <= toDate('{last_date.isoformat()}')
            """
        )

    mart_rows = int(count_result.result_rows[0][0])

    LOGGER.info(
        "Report mart rebuilt: first_date=%s, last_date=%s, "
        "crm_rows=%s, telemetry_rows=%s, mart_rows=%s",
        first_date,
        last_date,
        crm_rows,
        telemetry_rows,
        mart_rows,
    )

    return mart_rows


@task(
    task_id="update_etl_watermark",
    retries=2,
    retry_delay=timedelta(seconds=15),
)
def update_etl_watermark(
    window: dict[str, str],
    mart_rows: int,
) -> None:
    """
    Обновляет границу полностью обработанных данных.

    Task запускается только после успешной сборки витрины, поэтому
    watermark не продвинется при ошибке extraction, loading или
    aggregation.
    """
    processed_until = parse_iso_datetime(window["end"])
    updated_at = datetime.now(timezone.utc)

    with clickhouse_client() as client:
        client.insert(
            "reports_olap.etl_watermark",
            [
                [
                    PIPELINE_NAME,
                    processed_until,
                    "SUCCESS",
                    updated_at,
                ]
            ],
            column_names=[
                "pipeline_name",
                "processed_until",
                "status",
                "updated_at",
            ],
        )

    LOGGER.info(
        "ETL watermark updated: processed_until=%s, mart_rows=%s",
        processed_until.isoformat(),
        mart_rows,
    )


with DAG(
    dag_id=PIPELINE_NAME,
    description=(
        "ETL CRM and prosthesis telemetry into the user report mart"
    ),
    schedule=CronDataIntervalTimetable(
        "0 * * * *",
        timezone="UTC",
    ),
    start_date=pendulum.datetime(
        2026,
        6,
        1,
        tz="UTC",
    ),
    catchup=False,
    max_active_runs=1,
    default_args={
        "owner": "bionicpro",
        "depends_on_past": False,
    },
    tags=[
        "bionicpro",
        "reports",
        "etl",
    ],
) as dag:
    processing_window = resolve_processing_window()

    crm_row_count = load_crm_snapshot()

    telemetry_row_count = load_telemetry_interval(
        processing_window
    )

    report_row_count = build_user_report_mart(
        processing_window,
        crm_row_count,
        telemetry_row_count,
    )

    update_etl_watermark(
        processing_window,
        report_row_count,
    )
