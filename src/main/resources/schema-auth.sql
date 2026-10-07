-- ---------------------------------------------------------------------------
-- User authentication (lives in the history database).
-- Applied automatically at application startup by UserService; you can also run it by hand.
-- Safe to re-run: every statement is idempotent.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'VIEWER',          -- ADMIN | DEPLOYER | VIEWER
    must_change_password BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW(),
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

-- Tokens issued before this moment are rejected (password change / admin reset ends old sessions).
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
-- Set by "Forgot password?" on the login page; shown to the SuperAdmin, cleared on reset.
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_requested_at TIMESTAMP;

-- Initial data: the SuperAdmin account is created by the application on first startup
-- (username/password from app.auth.superadmin-default-*, hashed with bcrypt, must_change_password = TRUE),
-- because a bcrypt hash cannot be computed in plain SQL. Equivalent to:
--   INSERT INTO users (username, password_hash, role, must_change_password)
--   VALUES ('admin', bcrypt_hash('admin123'), 'ADMIN', TRUE);

-- ---------------------------------------------------------------------------
-- Audit trail: who started each deployment.
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS deployment_history ADD COLUMN IF NOT EXISTS deployed_by VARCHAR(50);
