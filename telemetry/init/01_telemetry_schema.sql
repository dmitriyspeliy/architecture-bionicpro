CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE prosthesis_telemetry
(
    event_id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    prosthesis_id     UUID        NOT NULL,
    event_time        TIMESTAMPTZ NOT NULL,
    usage_seconds     INTEGER     NOT NULL DEFAULT 0,
    battery_level     NUMERIC(5, 2),
    temperature_c     NUMERIC(5, 2),
    alert_code        VARCHAR(50),
    inserted_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_prosthesis_telemetry
        PRIMARY KEY (event_id),

    CONSTRAINT chk_usage_seconds
        CHECK (usage_seconds >= 0),

    CONSTRAINT chk_battery_level
        CHECK (
            battery_level IS NULL
                OR battery_level BETWEEN 0 AND 100
            )
);

CREATE INDEX idx_telemetry_prosthesis_event_time
    ON prosthesis_telemetry (prosthesis_id, event_time);

CREATE INDEX idx_telemetry_event_time
    ON prosthesis_telemetry (event_time);