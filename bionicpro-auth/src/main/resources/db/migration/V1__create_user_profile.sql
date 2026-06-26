CREATE TABLE user_profile
(
    id                     UUID         NOT NULL,
    issuer                 VARCHAR(255) NOT NULL,
    subject                VARCHAR(255) NOT NULL,
    username               VARCHAR(255) NOT NULL,
    email                  VARCHAR(320),
    first_name             VARCHAR(255),
    last_name              VARCHAR(255),
    identity_provider      VARCHAR(100),
    consent_granted_at     TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_user_profile
        PRIMARY KEY (id),

    CONSTRAINT uk_user_profile_issuer_subject
        UNIQUE (issuer, subject)
);

CREATE INDEX idx_user_profile_email
    ON user_profile (email);

CREATE INDEX idx_user_profile_identity_provider
    ON user_profile (identity_provider);