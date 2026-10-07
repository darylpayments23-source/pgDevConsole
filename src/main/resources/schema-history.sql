CREATE TABLE IF NOT EXISTS deployment_history (
    id UUID PRIMARY KEY,
    environment VARCHAR(30) NOT NULL,
    folder TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_scripts INTEGER NOT NULL,
    successful_scripts INTEGER NOT NULL DEFAULT 0,
    failed_scripts INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_deployment_history_started
    ON deployment_history(started_at DESC);

-- ---------------------------------------------------------------------------
-- Session persistence (progress survives page refresh / server restart)
-- Safe to re-run: every statement is idempotent.
-- ---------------------------------------------------------------------------
ALTER TABLE deployment_history ADD COLUMN IF NOT EXISTS commit_mode   VARCHAR(10) NOT NULL DEFAULT 'script';
ALTER TABLE deployment_history ADD COLUMN IF NOT EXISTS error         TEXT;
ALTER TABLE deployment_history ADD COLUMN IF NOT EXISTS current_index INTEGER     NOT NULL DEFAULT -1;

CREATE TABLE IF NOT EXISTS deployment_script_history (
    deployment_id UUID         NOT NULL REFERENCES deployment_history(id) ON DELETE CASCADE,
    script_order  INTEGER      NOT NULL,
    path          TEXT         NOT NULL,
    filename      TEXT         NOT NULL,
    sequence      BIGINT       NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    duration_ms   BIGINT,
    error         TEXT,
    PRIMARY KEY (deployment_id, script_order)
);

-- ---------------------------------------------------------------------------
-- Distributed deployment locking (one active deployment per environment,
-- across all application instances sharing this history database).
-- Safe to re-run: every statement is idempotent.
-- Timestamps are stored in UTC so instances in different time zones agree.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS deployment_lock (
    id SERIAL PRIMARY KEY,
    environment VARCHAR(30) NOT NULL UNIQUE,
    deployment_id UUID,
    locked_at TIMESTAMP NOT NULL,
    locked_until TIMESTAMP NOT NULL
);

-- ---------------------------------------------------------------------------
-- Script checksum validation (SHA-256 of each script, detects post-execution edits).
-- Safe to re-run: every statement is idempotent.
-- ---------------------------------------------------------------------------
ALTER TABLE deployment_script_history ADD COLUMN IF NOT EXISTS checksum VARCHAR(64);
ALTER TABLE deployment_script_history ADD COLUMN IF NOT EXISTS checksum_changed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_script_history_path_checksum
    ON deployment_script_history(path, status) WHERE checksum IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Audit trail: username that started each deployment (see also schema-auth.sql).
-- Safe to re-run: every statement is idempotent.
-- ---------------------------------------------------------------------------
ALTER TABLE deployment_history ADD COLUMN IF NOT EXISTS deployed_by VARCHAR(50);

-- ---------------------------------------------------------------------------
-- History pagination & filtering (GET /api/history?environment=&status=&deployedBy=&from=&to=&page=&size=).
-- Safe to re-run: every statement is idempotent.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_deployment_history_env_started
    ON deployment_history(lower(environment), started_at DESC);
CREATE INDEX IF NOT EXISTS idx_deployment_history_status_started
    ON deployment_history(status, started_at DESC);
