package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * Database-level, per-environment deployment lock shared by every application instance that uses the
 * same history database (table {@code deployment_lock}, see schema-history.sql).
 *
 * <ul>
 *   <li>Acquisition is a single atomic {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE} statement, so
 *       two instances can never both win the lock.</li>
 *   <li>A lock whose {@code locked_until} has passed is treated as free (auto-expiry after a crash).</li>
 *   <li>All time arithmetic uses the database clock in UTC, so instance clocks / JVM time zones don't matter.</li>
 *   <li>Locks are per environment, so different environments can be deployed concurrently.</li>
 * </ul>
 */
@Service
public class DeploymentLockService {

    /** Details of an active lock. {@code remainingSeconds} is computed by the database clock. */
    public record LockInfo(String environment, String deploymentId, Instant lockedAt,
                           Instant lockedUntil, long remainingSeconds) {}

    private static final String NOW_UTC = "(now() AT TIME ZONE 'UTC')";

    private final AppProperties props;

    public DeploymentLockService(AppProperties props) { this.props = props; }

    private Connection connect() throws SQLException {
        var e = props.getExecution();
        return DriverManager.getConnection(e.getHistoryDb(), e.getHistoryUsername(), e.getHistoryPassword());
    }

    private static String key(String environment) {
        if (environment == null || environment.isBlank())
            throw new IllegalArgumentException("Environment is required");
        return environment.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Tries to take the lock for {@code environment}.
     *
     * @return true if the lock was acquired; false if another (unexpired) deployment holds it
     * @throws IllegalStateException if the lock database cannot be reached (fail closed: never deploy unlocked)
     */
    public boolean acquireLock(String environment, String deploymentId, int timeoutMinutes) {
        if (timeoutMinutes <= 0) throw new IllegalArgumentException("Lock timeout must be positive");
        String sql = """
            INSERT INTO deployment_lock (environment, deployment_id, locked_at, locked_until)
            VALUES (?, ?, %1$s, %1$s + (? * interval '1 minute'))
            ON CONFLICT (environment) DO UPDATE
               SET deployment_id = EXCLUDED.deployment_id,
                   locked_at     = EXCLUDED.locked_at,
                   locked_until  = EXCLUDED.locked_until
             WHERE deployment_lock.deployment_id IS NULL
                OR deployment_lock.locked_until <= EXCLUDED.locked_at
            """.formatted(NOW_UTC);
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, key(environment));
            p.setObject(2, UUID.fromString(deploymentId));
            p.setInt(3, timeoutMinutes);
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not acquire deployment lock: " + e.getMessage(), e);
        }
    }

    /** Releases the lock, but only if it is still held by {@code deploymentId} (never frees someone else's lock). */
    public void releaseLock(String environment, String deploymentId) {
        try (Connection c = connect();
             PreparedStatement p = c.prepareStatement(
                     "DELETE FROM deployment_lock WHERE environment = ? AND deployment_id = ?")) {
            p.setString(1, key(environment));
            p.setObject(2, UUID.fromString(deploymentId));
            p.executeUpdate();
        } catch (SQLException e) {
            // The lock will still expire on its own; don't fail the deployment because of this.
            System.err.println("Could not release deployment lock for " + environment + ": " + e.getMessage());
        }
    }

    /** The active (unexpired) lock for the environment, or null if it is free. */
    public LockInfo getLockInfo(String environment) {
        String sql = """
            SELECT environment, deployment_id, locked_at, locked_until,
                   EXTRACT(EPOCH FROM (locked_until - %1$s)) AS remaining
              FROM deployment_lock
             WHERE environment = ?
               AND deployment_id IS NOT NULL
               AND locked_until > %1$s
            """.formatted(NOW_UTC);
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, key(environment));
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return new LockInfo(
                        rs.getString("environment"),
                        rs.getString("deployment_id"),
                        rs.getObject("locked_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("locked_until", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        Math.max(0, (long) Math.ceil(rs.getDouble("remaining"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read deployment lock: " + e.getMessage(), e);
        }
    }
}
