# PostgreSQL Deployment Console

A Spring Boot 3 / Java 21 web application for controlled PostgreSQL SQL-script deployment.

## Features

- Browser UI
- SQL root folder input
- DEV / UAT / PROD environment selection
- Recursive SQL script discovery
- Filename-sequence execution order
- Dry-run-like Scan step before execution
- Sequential PostgreSQL execution
- Commit-per-script or commit-all mode
- Real-time execution status through Server-Sent Events (SSE)
- Per-script duration and error display
- Deployment history API/UI
- Distributed deployment locking (one deployment per environment across all instances)
- PostgreSQL dollar-quoted function/procedure support
- Safe relative-path resolution to prevent path traversal
- User authentication (JWT) with a bootstrap SuperAdmin, temporary passwords and forced password change
- Role-based access (ADMIN / DEPLOYER / VIEWER) and a per-deployment audit trail (who deployed what)

## Important configuration

Edit:

src/main/resources/application.yml

Example:

```yaml
app:
  databases:
    dev:
      name: DEV
      url: jdbc:postgresql://server:5432/app_dev
      username: deploy_user
      password: ${DEV_DB_PASSWORD}
```

For production, DO NOT commit passwords to Git. Use environment variables or a secrets manager.

## Build

Requirements:
- JDK 21
- Maven 3.9+
- PostgreSQL

Run:

```bash
mvn clean package
```

Start:

```bash
java -jar target/postgres-deployment-console-1.0.0.jar
```

Open:

http://localhost:8080

## API

### Scan

```http
POST /api/scan?folder=C:\deploy\release-001
```

### Start deployment

```http
POST /api/deploy
Content-Type: application/json

{
  "folder": "C:\\deploy\\release-001",
  "environment": "uat",
  "commitMode": "script"
}
```

### Deployment status

```http
GET /api/deploy/{id}
```

### Real-time events

```http
GET /api/deploy/{id}/events
Accept: text/event-stream
```

### Active deployment (used to re-attach after a page refresh)

```http
GET /api/deploy/active
```

Returns `{"id": "..."}` for the queued/running deployment, or `204 No Content` if none.

### Lock status

```http
GET /api/deploy/{environment}/lock-status
```

Returns `{"environment":"prod","locked":true,"lock":{"environment":"prod","deploymentId":"...","lockedAt":"...","lockedUntil":"...","remainingSeconds":1234}}`,
or `"locked":false,"lock":null` when the environment is free.

### Environments

```http
GET /api/environments
```

### History

```http
GET /api/history
```

## Filename convention

Recommended:

```text
001_create_schema.sql
002_create_tables.sql
003_reference_data.sql
010_create_views.sql
020_create_functions.sql
```

Also supported:

```text
V1__create_schema.sql
V2__create_tables.sql
V10__create_views.sql
```

All SQL files under the selected root folder are sorted globally by sequence, regardless of sub-folder.

## Production considerations

This starter project intentionally keeps authentication and secret management simple. Before exposing it to users or production networks, add:

1. ~~Role-based access.~~ (implemented: JWT login, roles, see "User authentication")
2. Secure secret management.
3. CSRF protection for browser mutations.
4. Persistent deployment-history writes.
5. ~~Deployment locking / one deployment per target database.~~ (implemented, see below)
6. Script checksum validation.
7. Explicit PROD confirmation.
8. ~~Audit user identity.~~ (implemented: `deployment_history.deployed_by` + `AUDIT` log lines)
9. Connection and query timeout policy.
10. Backup/recovery validation.
11. Centralized application logging.
12. HTTPS/reverse proxy.

## Progress persistence

Deployment progress now survives a browser refresh and an application restart:

- The browser remembers the last deployment ID (localStorage) and, on load, re-attaches to the running deployment (or shows the last result), restoring folder, environment and transaction mode.
- Per-script status is written to `deployment_script_history` as each script starts/finishes.
- On startup, any deployment still marked PENDING/RUNNING from a previous process is marked FAILED ("Interrupted") and its unfinished scripts SKIPPED.

**Re-run `src/main/resources/schema-history.sql`** against the history database to add the new columns/table (it is idempotent).

## Note about execution history

The included HistoryService expects the `deployment_history` table to exist in the configured history database. Run `src/main/resources/schema-history.sql` against that database before using the History screen.

History and per-script progress are persisted by DeploymentService via HistoryService.

## Distributed deployment locking

Only one deployment per environment can run at a time, across every application instance that shares the same history database.

- Locks live in the `deployment_lock` table (one row per environment). **Re-run `src/main/resources/schema-history.sql`** to create it (idempotent).
- `POST /api/deploy` acquires the lock atomically before doing any work. If the environment is already locked it returns **409 Conflict**:

  ```json
  {"error":"DEPLOYMENT_LOCKED","message":"PROD is locked by deployment <id> (lock expires in 1500s).",
   "environment":"prod","lock":{"deploymentId":"<id>","lockedAt":"...","lockedUntil":"...","remainingSeconds":1500}}
  ```
- The lock is released when the deployment finishes (success or failure). Only the owning deployment can release it.
- Locks expire automatically after `app.execution.lock-timeout-minutes` (default **30**), so a crashed instance cannot block an environment forever.
- Locks are per environment: DEV, UAT and PROD can be deployed concurrently.
- All lock timing uses the database clock (UTC), so instance clocks and time zones don't matter.
- The UI shows a banner with the lock holder's deployment ID and a live countdown, and refreshes it for the selected environment every 10 seconds.

Set the timeout longer than your slowest expected deployment: a lock that expires while a deployment is still running no longer protects the environment.

## Script checksum validation

Every script is hashed (SHA-256) and the checksum is stored per script in `deployment_script_history.checksum`.

- **Re-run `src/main/resources/schema-history.sql`** to add the `checksum` / `checksum_changed` columns (idempotent).
- On **scan** and **deploy**, each script's checksum is compared with the checksum of its most recent *successful* execution in the same environment (matched by relative script path). A difference is logged server-side, shown as a **MODIFIED** badge in the UI and as `⚠️ Script <path> has changed since last execution` in the deployment log.
- With `app.execution.require-confirmation-for-modified: true` (default), `POST /api/deploy` answers `409 {"error":"SCRIPTS_MODIFIED","modifiedScripts":[...]}` until the request is resent with `"confirmModified": true` (the UI shows a confirmation dialog). Set it to `false` to warn only.
- The executor hashes the exact bytes it runs; if a script changes between deploy start and its execution, that script fails instead of running.
- `GET`-style lookup: `HistoryService.getPreviousChecksum(environment, scriptPath)`.

## User authentication

The console now requires a login. Users are stored in the history database (table `users`).

### First startup

- `src/main/resources/schema-auth.sql` is applied automatically at startup (idempotent; you can also run it by hand).
  It creates the `users` table and adds `deployment_history.deployed_by`.
- A SuperAdmin account is created if it does not exist: **`admin` / `admin123`**, role `ADMIN`.
  It must change its password at first login.
- **Production:** set `SUPERADMIN_PASSWORD` (initial SuperAdmin password) and `JWT_SECRET` (long random string)
  as environment variables, and serve the console over HTTPS.

```yaml
app:
  auth:
    jwt-secret: ${JWT_SECRET:change-me-in-production}
    jwt-expiry-hours: 24
    superadmin-default-username: admin
    superadmin-default-password: ${SUPERADMIN_PASSWORD:admin123}
    bcrypt-strength: 10        # bcrypt salt rounds
    min-password-length: 8
```

### User flow

1. The SuperAdmin logs in at `/login`, changes the default password, and opens **Users** in the console.
2. The SuperAdmin creates a user (username + role) and receives a **temporary password** (shown once).
3. The user logs in with the temporary password and is forced to choose a new one (no old password needed at that step,
   because they have just entered it).
4. The user lands on the deployment console. **Change Password** (old password required) and **Logout** are in the top bar.
5. "Forgot password?" on the login page flags the account; the SuperAdmin sees **RESET REQUESTED** in the user list and
   uses **Reset password** to issue a new temporary password.

### Roles

| Role | Can do |
|---|---|
| `ADMIN` | Everything, including user management (SuperAdmin) |
| `DEPLOYER` | Scan, execute deployments, view status/history |
| `VIEWER` (default) | Scan and view status/history; cannot execute |

### Sessions and security

- Login returns a JWT (HS256, 24 h). The browser keeps it in **sessionStorage** and sends `Authorization: Bearer <token>`.
- `JwtAuthFilter` protects every `/api/**` request except `POST /api/auth/login` and `POST /api/auth/forgot-password`.
  Missing/invalid/expired tokens get `401`, and the UI redirects to the login page (also automatically at token expiry).
- While a password change is pending, only `/api/auth/me`, `/api/auth/change-password` and `/api/auth/logout` work
  (`403 PASSWORD_CHANGE_REQUIRED`).
- Changing or resetting a password ends all earlier sessions of that user; deactivating a user ends them immediately.
- Logout revokes the token server-side (in-memory deny-list; with several instances, a logged-out token stays valid on
  the other instances until it expires).
- Passwords are hashed with bcrypt (cost 10). Login failures do not reveal whether a username exists.
- Controllers declare access with `@RequireAuth` / `@RequireAuth(roles = ...)`.
- The deployment progress stream (`/api/deploy/{id}/events`) is read with `fetch()` because `EventSource` cannot send
  the Authorization header.

### Audit trail

- Each deployment stores the username in `deployment_history.deployed_by`; the History table shows **Deployed by**.
- The server logs `AUDIT` lines for logins, password changes/resets, user admin actions, and every deployment
  request, block, start and finish, each with the username.

### Auth API

```http
POST /api/auth/login              {"username":"...","password":"..."}  -> {token, username, role, mustChangePassword, expiresAt}
POST /api/auth/change-password    {"oldPassword":"...","newPassword":"..."} -> {success, token, ...}  (new token)
GET  /api/auth/me                 -> {username, role, mustChangePassword, expiresAt, minPasswordLength}
POST /api/auth/logout             -> {success}
POST /api/auth/forgot-password    {"username":"..."}  -> {success, message}
```

SuperAdmin only (`ADMIN`):

```http
POST   /api/admin/users                            {"username":"...","role":"VIEWER|DEPLOYER|ADMIN"} -> 201 {username, role, temporaryPassword}
GET    /api/admin/users                            -> [{username, role, active, mustChangePassword, createdBy, createdAt, lastLogin, resetRequestedAt}]
DELETE /api/admin/users/{username}                 -> deactivate (cannot deactivate yourself or the last active ADMIN)
POST   /api/admin/users/{username}/activate        -> re-activate
POST   /api/admin/users/{username}/reset-password  -> {username, role, temporaryPassword}
```

All other API calls documented above now need the `Authorization: Bearer <token>` header; `POST /api/deploy`
needs role `ADMIN` or `DEPLOYER`.
