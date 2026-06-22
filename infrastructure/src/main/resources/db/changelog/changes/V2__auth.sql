CREATE TABLE IF NOT EXISTS app_users (
    id            UUID         PRIMARY KEY,
    google_sub    VARCHAR(64)  NOT NULL UNIQUE,
    email         VARCHAR(320) NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    picture_url   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    last_login_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    jti         UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    issued_at   TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_user    ON auth_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires ON auth_sessions (expires_at);