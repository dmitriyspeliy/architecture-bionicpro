CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE crm_customer
(
    customer_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_subject      VARCHAR(255) NOT NULL,
    email             VARCHAR(320) NOT NULL,
    full_name         VARCHAR(255) NOT NULL,
    region            VARCHAR(100),
    prosthesis_id     UUID         NOT NULL,
    prosthesis_model  VARCHAR(100) NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_crm_customer
        PRIMARY KEY (customer_id),

    CONSTRAINT uk_crm_customer_user_subject
        UNIQUE (user_subject),

    CONSTRAINT uk_crm_customer_prosthesis
        UNIQUE (prosthesis_id),

    CONSTRAINT chk_crm_customer_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_crm_customer_updated_at
    ON crm_customer (updated_at);