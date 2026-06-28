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
    """
    Возвращает обязательную переменную окружения.

    Если переменная отсутствует или содержит пустое значение,
    выполнение DAG завершается с явной ошибкой конфигурации.
    """
    value = os.getenv(name)

    if value is None or value.strip() == "":
        raise RuntimeError(
            f"Required environment variable is not configured: {name}"
        )

    return value


def telemetry_connection():
    """
    Создаёт соединение с PostgreSQL телеметрии.

    CRM PostgreSQL здесь намеренно не используется:
    изменения CRM поступают в ClickHouse через CDC-поток:

        PostgreSQL WAL
        -> Debezium
        -> Kafka
        -> ClickHouse KafkaEngine
        -> Materialized View.
    """
    return psycopg.connect(
        host=required_env("TELEMETRY_DB_HOST"),
        port=int(required_env("TELEMETRY_DB_PORT")),
        dbname=required_env("TELEMETRY_DB_NAME"),
        user=required_env("TELEMETRY_DB_USERNAME"),
        password=required_env("TELEMETRY_DB_PASSWORD"),
        row_factory=dict_row,
    )


def clickhouse_client():
    """
    Создаёт HTTP-клиент ClickHouse.
    """
    return clickhouse_connect.get_client(
        host=required_env("CLICKHOUSE_HOST"),
        port=int(required_env("CLICKHOUSE_PORT")),
        database=required_env("CLICKHOUSE_DATABASE"),
        username=required_env("CLICKHOUSE_USERNAME"),
        password=required_env("CLICKHOUSE_PASSWORD"),
    )


def as_utc(value: datetime) -> datetime:
    """
    Нормализует datetime в UTC.

    Если timezone отсутствует, значение интерпретируется как UTC.
    """
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)

    return value.astimezone(timezone.utc)


def parse_iso_datetime(value: str) -> datetime:
    """
    Преобразует ISO-строку из XCom в timezone-aware datetime.
    """
    return as_utc(datetime.fromisoformat(value))


def clickhouse_datetime(value: datetime) -> str:
    """
    Форматирует datetime для литерала ClickHouse DateTime64(3, 'UTC').
    """
    return as_utc(value).strftime(
        "%Y-%m-%d %H:%M:%S.%f"
    )[:-3]


@task(
    task_id="resolve_processing_window",
    retries=2,
    retry_delay=timedelta(seconds=15),
)
def resolve_processing_window() -> dict[str, str]:
    """
    Определяет интервал телеметрии для текущего запуска DAG.

    Первый запуск:
        data_interval_end - 30 дней -> data_interval_end.

    Последующие запуски:
        последний успешный processed_until -> data_interval_end.

    Если Airflow был остановлен, следующий запуск обрабатывает весь
    пропущенный диапазон от watermark до текущего полного часа.
    """
    context = get_current_context()

    scheduled_start_value = context.get(
        "data_interval_start"
    )

    scheduled_end_value = context.get(
        "data_interval_end"
    )

    if (
            scheduled_start_value is None
            or scheduled_end_value is None
    ):

        scheduled_end = datetime.now(
            timezone.utc
        ).replace(
            minute=0,
            second=0,
            microsecond=0,
        )

        scheduled_start = (
                scheduled_end - timedelta(hours=1)
        )

        LOGGER.info(
            "Manual DAG run without data interval. "
            "Using the last completed hour: start=%s, end=%s",
            scheduled_start.isoformat(),
            scheduled_end.isoformat(),
        )
    else:
        scheduled_start = as_utc(
            scheduled_start_value
        )

        scheduled_end = as_utc(
            scheduled_end_value
        )

    with clickhouse_client() as client:
        result = client.query(
            """
            SELECT
                argMax(
                    processed_until,
                    updated_at
                ) AS processed_until,

                argMax(
                    status,
                    updated_at
                ) AS status

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
                scheduled_end
                - timedelta(days=INITIAL_LOOKBACK_DAYS)
        )

        previous_status = "NOT_FOUND"
    else:
        processed_until = as_utc(rows[0][0])
        previous_status = str(rows[0][1])

        if (
                previous_status == "NEVER_RUN"
                or processed_until.year <= 1970
        ):
            processing_start = (
                    scheduled_end
                    - timedelta(days=INITIAL_LOOKBACK_DAYS)
            )

        elif processed_until >= scheduled_end:

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
        "Resolved ETL window: "
        "start=%s, end=%s, previous_status=%s",
        window["start"],
        window["end"],
        previous_status,
    )

    return window


@task(
    task_id="load_telemetry_interval",
    retries=3,
    retry_delay=timedelta(seconds=20),
)
def load_telemetry_interval(
        window: dict[str, str],
) -> int:
    """
    Загружает телеметрию за полуинтервал [start, end).

    Перед вставкой строки того же интервала удаляются из staging.
    Поэтому повторный запуск DAG не создаёт дубликаты.

    CRM PostgreSQL в этой задаче не читается.
    """
    interval_start = parse_iso_datetime(
        window["start"]
    )

    interval_end = parse_iso_datetime(
        window["end"]
    )

    with telemetry_connection() as connection:
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

                ORDER BY
                    event_time,
                    event_id
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

    start_sql = clickhouse_datetime(
        interval_start
    )

    end_sql = clickhouse_datetime(
        interval_end
    )

    with clickhouse_client() as client:
        client.command(
            f"""
            ALTER TABLE
                reports_olap.stg_prosthesis_telemetry

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
        "Telemetry interval loaded: "
        "start=%s, end=%s, rows=%s",
        interval_start.isoformat(),
        interval_end.isoformat(),
        len(clickhouse_rows),
    )

    return len(clickhouse_rows)


@task(
    task_id="refresh_user_report_mart",
    retries=2,
    retry_delay=timedelta(seconds=20),
)
def refresh_user_report_mart(
        window: dict[str, str],
        telemetry_rows: int,
) -> int:
    """
    Обновляет итоговую отчётную витрину после загрузки телеметрии.

    Витрина объединяет:

    - телеметрию из stg_prosthesis_telemetry;
    - актуальное состояние CRM из crm_customer_cdc_state.

    CRM-данные уже находятся в ClickHouse благодаря CDC,
    поэтому массовый SELECT из PostgreSQL CRM не выполняется.
    """
    interval_start = parse_iso_datetime(
        window["start"]
    )

    interval_end = parse_iso_datetime(
        window["end"]
    )

    first_date = interval_start.date()

    last_date = (
            interval_end - timedelta(microseconds=1)
    ).date()

    with clickhouse_client() as client:

        client.command(
            """
            SYSTEM REFRESH VIEW
                reports_olap.user_report_daily_cdc_mv
            """
        )


        client.command(
            """
            SYSTEM WAIT VIEW
                reports_olap.user_report_daily_cdc_mv
            """
        )

        refresh_result = client.query(
            """
            SELECT
                status,
                exception,
                written_rows

            FROM system.view_refreshes

            WHERE database = 'reports_olap'
              AND view = 'user_report_daily_cdc_mv'
            """
        )

        if not refresh_result.result_rows:
            raise RuntimeError(
                "CDC report materialized view was not found"
            )

        (
            refresh_status,
            refresh_exception,
            written_rows,
        ) = refresh_result.result_rows[0]

        if refresh_exception:
            raise RuntimeError(
                "CDC report materialized view refresh failed: "
                f"{refresh_exception}"
            )

        count_result = client.query(
            """
            SELECT count()

            FROM reports_olap.user_report_daily_cdc

            WHERE report_date >= toDate(
                {first_date:String}
            )
              AND report_date <= toDate(
                {last_date:String}
            )
            """,
            parameters={
                "first_date": first_date.isoformat(),
                "last_date": last_date.isoformat(),
            },
        )

    mart_rows = int(
        count_result.result_rows[0][0]
    )

    LOGGER.info(
        "CDC report mart refreshed: "
        "first_date=%s, last_date=%s, "
        "telemetry_rows=%s, written_rows=%s, "
        "mart_rows=%s, status=%s",
        first_date,
        last_date,
        telemetry_rows,
        written_rows,
        mart_rows,
        refresh_status,
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
    Обновляет верхнюю границу обработанных данных.

    Task выполняется только после:

    1. успешной загрузки телеметрии;
    2. успешного обновления CDC-витрины.

    Поэтому watermark не продвигается при ошибке extraction,
    загрузки телеметрии или refreshable Materialized View.
    """
    processed_until = parse_iso_datetime(
        window["end"]
    )

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
        "ETL watermark updated: "
        "processed_until=%s, mart_rows=%s",
        processed_until.isoformat(),
        mart_rows,
    )


with DAG(
        dag_id=PIPELINE_NAME,
        description=(
                "Load prosthesis telemetry and refresh the "
                "CDC-based user report mart"
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
            "cdc",
        ],
) as dag:
    processing_window = (
        resolve_processing_window()
    )

    telemetry_row_count = (
        load_telemetry_interval(
            processing_window
        )
    )

    report_row_count = (
        refresh_user_report_mart(
            processing_window,
            telemetry_row_count,
        )
    )

    update_etl_watermark(
        processing_window,
        report_row_count,
    )