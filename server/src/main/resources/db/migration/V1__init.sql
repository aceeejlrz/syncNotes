-- SyncNotes schema v1. Source of truth: ARCHITECTURE.md section 8.1 (copied verbatim).
-- Never edit an applied migration; add V2__... instead.

CREATE TABLE users (
    id            UUID        PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,        -- stored lowercased
    password_hash TEXT        NOT NULL,
    sync_version  BIGINT      NOT NULL DEFAULT 0,     -- per-user change counter (section 9.3)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notes (
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id              UUID        NOT NULL,             -- generated on a device
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    pinned          BOOLEAN     NOT NULL,
    trashed         BOOLEAN     NOT NULL,
    -- per field: the change number of its last write, and which device wrote it
    title_version   BIGINT      NOT NULL,
    title_device    UUID        NOT NULL,
    body_version    BIGINT      NOT NULL,
    body_device     UUID        NOT NULL,
    pinned_version  BIGINT      NOT NULL,
    pinned_device   UUID        NOT NULL,
    trashed_version BIGINT      NOT NULL,
    trashed_device  UUID        NOT NULL,
    server_version  BIGINT      NOT NULL,             -- change number of the latest write to this row
    created_at      TIMESTAMPTZ NOT NULL,             -- reported by the creating device
    updated_at      TIMESTAMPTZ NOT NULL,             -- server time of the latest write
    PRIMARY KEY (user_id, id),
    CONSTRAINT notes_title_len CHECK (char_length(title) <= 300),
    CONSTRAINT notes_body_len  CHECK (char_length(body) <= 100000)
);

-- Serves the pull query and guarantees no two rows share a change number.
CREATE UNIQUE INDEX notes_user_version_idx ON notes (user_id, server_version);

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,          -- the raw token is never stored
    device_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
