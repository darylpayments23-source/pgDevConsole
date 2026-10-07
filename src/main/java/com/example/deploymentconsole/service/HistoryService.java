package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.model.DeploymentDetail;
import com.example.deploymentconsole.model.HistoryQuery;
import com.example.deploymentconsole.model.PageResult;
import com.example.deploymentconsole.model.ScriptInfo;
import com.example.deploymentconsole.model.ScriptStatus;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class HistoryService {
    private final AppProperties props;

    public HistoryService(AppProperties props){ this.props=props; }

    private Connection connect() throws SQLException {
        var e = props.getExecution();
        return DriverManager.getConnection(e.getHistoryDb(), e.getHistoryUsername(), e.getHistoryPassword());
    }

    /** Persists the deployment header and every script row. */
    public void save(DeploymentService.DeploymentState s) {
        persist(s, new ArrayList<>(s.scripts));
    }

    /** Persists the deployment header and a single script row (cheap; used after each script status change). */
    public void saveProgress(DeploymentService.DeploymentState s, int index) {
        persist(s, List.of(s.scripts.get(index)));
    }

    private void persist(DeploymentService.DeploymentState s, List<ScriptInfo> scripts) {
        String header = """
            INSERT INTO deployment_history
              (id, environment, folder, status, total_scripts,
               successful_scripts, failed_scripts, started_at, completed_at,
               commit_mode, error, current_index, deployed_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              status=EXCLUDED.status,
              successful_scripts=EXCLUDED.successful_scripts,
              failed_scripts=EXCLUDED.failed_scripts,
              started_at=EXCLUDED.started_at,
              completed_at=EXCLUDED.completed_at,
              error=EXCLUDED.error,
              current_index=EXCLUDED.current_index
            """;
        String script = """
            INSERT INTO deployment_script_history
              (deployment_id, script_order, path, filename, sequence, status, duration_ms, error,
               checksum, checksum_changed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (deployment_id, script_order) DO UPDATE SET
              status=EXCLUDED.status,
              duration_ms=EXCLUDED.duration_ms,
              error=EXCLUDED.error,
              checksum=EXCLUDED.checksum,
              checksum_changed=EXCLUDED.checksum_changed
            """;
        List<ScriptInfo> all = new ArrayList<>(s.scripts);
        UUID id = UUID.fromString(s.id);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement(header)) {
                p.setObject(1, id);
                p.setString(2, s.environment);
                p.setString(3, s.folder);
                p.setString(4, s.status);
                p.setInt(5, all.size());
                p.setInt(6, (int) all.stream().filter(x -> x.status() == ScriptStatus.SUCCESS).count());
                p.setInt(7, (int) all.stream().filter(x -> x.status() == ScriptStatus.FAILED).count());
                p.setTimestamp(8, Timestamp.from(s.startedAt == null ? java.time.Instant.now() : s.startedAt));
                p.setTimestamp(9, s.completedAt == null ? null : Timestamp.from(s.completedAt));
                p.setString(10, s.commitMode);
                p.setString(11, s.error);
                p.setInt(12, s.current);
                p.setString(13, s.deployedBy);   // insert-only: the starter of a deployment never changes
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(script)) {
                for (ScriptInfo x : scripts) {
                    p.setObject(1, id);
                    p.setInt(2, x.order());
                    p.setString(3, x.path());
                    p.setString(4, x.filename());
                    p.setLong(5, x.sequence());
                    p.setString(6, x.status().name());
                    if (x.durationMs() == null) p.setNull(7, Types.BIGINT); else p.setLong(7, x.durationMs());
                    p.setString(8, x.error());
                    p.setString(9, x.checksum());
                    p.setBoolean(10, x.modified());
                    p.addBatch();
                }
                p.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            System.err.println("Could not write deployment history: " + e.getMessage());
        }
    }

    /** Rebuilds a deployment (including per-script status) from the history database, or null if unknown. */
    public DeploymentService.DeploymentState load(String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }

        try (Connection c = connect()) {
            String environment, folder, status, commitMode, error, deployedBy;
            int current;
            Timestamp started, completed;
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT environment, folder, status, commit_mode, error, current_index, started_at, completed_at,
                           deployed_by
                    FROM deployment_history WHERE id=?""")) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return null;
                    environment = rs.getString("environment");
                    folder = rs.getString("folder");
                    status = rs.getString("status");
                    commitMode = rs.getString("commit_mode");
                    error = rs.getString("error");
                    current = rs.getInt("current_index");
                    started = rs.getTimestamp("started_at");
                    completed = rs.getTimestamp("completed_at");
                    deployedBy = rs.getString("deployed_by");
                }
            }

            List<ScriptInfo> scripts = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT script_order, path, filename, sequence, status, duration_ms, error, checksum, checksum_changed
                    FROM deployment_script_history WHERE deployment_id=? ORDER BY script_order""")) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        long d = rs.getLong("duration_ms");
                        Long duration = rs.wasNull() ? null : d;
                        scripts.add(new ScriptInfo(rs.getInt("script_order"), rs.getString("path"),
                                rs.getString("filename"), rs.getLong("sequence"),
                                ScriptStatus.valueOf(rs.getString("status")), duration, rs.getString("error"),
                                rs.getString("checksum"), rs.getBoolean("checksum_changed")));
                    }
                }
            }

            var state = new DeploymentService.DeploymentState(id, environment, folder, scripts, commitMode);
            state.status = status;
            state.error = error;
            state.current = current;
            state.startedAt = started == null ? null : started.toInstant();
            state.completedAt = completed == null ? null : completed.toInstant();
            state.deployedBy = deployedBy;
            return state;
        } catch (SQLException e) {
            System.err.println("Could not load deployment " + id + ": " + e.getMessage());
            return null;
        }
    }

    /** Header of one deployment (bind: id). */
    static final String DETAIL_HEADER_SQL = """
            SELECT id, environment, folder, status, commit_mode, deployed_by, started_at, completed_at, error,
                   total_scripts, successful_scripts, failed_scripts
              FROM deployment_history
             WHERE id = ?""";

    /** Every script of one deployment in execution order (bind: id). Served by the (deployment_id, script_order) PK. */
    static final String DETAIL_SCRIPTS_SQL = """
            SELECT script_order, path, filename, sequence, status, duration_ms, error, checksum, checksum_changed
              FROM deployment_script_history
             WHERE deployment_id = ?
             ORDER BY script_order""";

    /**
     * One deployment with every script that ran under it and each script's derived {@link
     * com.example.deploymentconsole.model.ScriptOutcome outcome}. Read from the history database, so it works for
     * old deployments and after a restart.
     *
     * @return empty for an unknown or non-UUID id
     * @throws IllegalStateException if the history database cannot be read
     */
    public Optional<DeploymentDetail> detail(String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id == null ? "" : id.trim()); } catch (IllegalArgumentException e) { return Optional.empty(); }

        try (Connection c = connect()) {
            DeploymentDetail.Header header;
            try (PreparedStatement p = c.prepareStatement(DETAIL_HEADER_SQL)) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Timestamp started = rs.getTimestamp("started_at"), completed = rs.getTimestamp("completed_at");
                    header = new DeploymentDetail.Header(rs.getString("id"), rs.getString("environment"),
                            rs.getString("folder"), rs.getString("status"), rs.getString("commit_mode"),
                            rs.getString("deployed_by"),
                            started == null ? null : started.toInstant(),
                            completed == null ? null : completed.toInstant(),
                            rs.getString("error"), rs.getInt("total_scripts"),
                            rs.getInt("successful_scripts"), rs.getInt("failed_scripts"));
                }
            }
            List<DeploymentDetail.ScriptRow> rows = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(DETAIL_SCRIPTS_SQL)) {
                p.setObject(1, uuid);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        long d = rs.getLong("duration_ms");
                        Long duration = rs.wasNull() ? null : d;
                        rows.add(new DeploymentDetail.ScriptRow(rs.getInt("script_order"), rs.getString("path"),
                                rs.getString("filename"), rs.getLong("sequence"), rs.getString("status"),
                                duration, rs.getString("error"), rs.getString("checksum"),
                                rs.getBoolean("checksum_changed")));
                    }
                }
            }
            return Optional.of(DeploymentDetail.of(header, rows));
        } catch (SQLException e) {
            System.err.println("Could not read deployment " + id + ": " + e.getMessage());
            throw new IllegalStateException("The history database is unavailable.", e);
        }
    }

    // A script counts as "previously executed" only if it succeeded. In commit-all mode a script marked
    // SUCCESS is only really applied if the whole deployment succeeded, so require that too.
    private static final String PREVIOUS_FILTER = """
              s.status = 'SUCCESS'
              AND s.checksum IS NOT NULL
              AND (d.commit_mode = 'script' OR d.status = 'SUCCESS')
            """;

    /**
     * Checksum of the most recent successful execution of {@code scriptPath} in {@code environment},
     * or null if the script has never been successfully executed there.
     */
    public String getPreviousChecksum(String environment, String scriptPath) {
        String sql = """
            SELECT s.checksum
              FROM deployment_script_history s
              JOIN deployment_history d ON d.id = s.deployment_id
             WHERE lower(d.environment) = lower(?) AND s.path = ?
               AND """ + PREVIOUS_FILTER + """
             ORDER BY d.started_at DESC, s.script_order DESC
             LIMIT 1
            """;
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, environment);
            p.setString(2, scriptPath);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) {
            System.err.println("Could not read previous checksum for " + scriptPath + ": " + e.getMessage());
            return null;
        }
    }

    /** Same as {@link #getPreviousChecksum} for every script of an environment in one query (path -> checksum). */
    public Map<String,String> getPreviousChecksums(String environment) {
        String sql = """
            SELECT DISTINCT ON (s.path) s.path, s.checksum
              FROM deployment_script_history s
              JOIN deployment_history d ON d.id = s.deployment_id
             WHERE lower(d.environment) = lower(?)
               AND """ + PREVIOUS_FILTER + """
             ORDER BY s.path, d.started_at DESC, s.script_order DESC
            """;
        Map<String,String> out = new HashMap<>();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, environment);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            System.err.println("Could not read previous checksums for " + environment + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * Called once at startup. Anything still PENDING/RUNNING in the database belongs to a process that
     * no longer exists, so close it out instead of leaving it "running" forever.
     * (Assumes a single application instance writes to this history database.)
     */
    public void markInterrupted() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeUpdate("""
                UPDATE deployment_script_history
                   SET status='FAILED', error='Interrupted: application stopped during execution'
                 WHERE status='RUNNING'""");
            st.executeUpdate("""
                UPDATE deployment_script_history
                   SET status='SKIPPED'
                 WHERE status='PENDING'
                   AND deployment_id IN (SELECT id FROM deployment_history WHERE status IN ('PENDING','RUNNING'))""");
            st.executeUpdate("""
                UPDATE deployment_history
                   SET status='FAILED',
                       error='Interrupted: application stopped before the deployment finished',
                       completed_at=COALESCE(completed_at, now()),
                       failed_scripts=(SELECT COUNT(*) FROM deployment_script_history s
                                        WHERE s.deployment_id=deployment_history.id AND s.status='FAILED')
                 WHERE status IN ('PENDING','RUNNING')""");
            c.commit();
        } catch (SQLException e) {
            System.err.println("Could not recover interrupted deployments: " + e.getMessage());
        }
    }

    private static void bind(PreparedStatement p, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) p.setObject(i + 1, params.get(i));
    }

    /** First page of history, newest first, no filters. */
    public PageResult<Map<String,Object>> list() {
        return list(HistoryQuery.firstPage());
    }

    /**
     * One page of deployment history matching {@code q}, newest first, with the total count for the same filters.
     *
     * @throws IllegalStateException if the history database cannot be read
     */
    public PageResult<Map<String,Object>> list(HistoryQuery q) {
        HistoryFilterSql.Where w = HistoryFilterSql.where(q);
        String countSql = HistoryFilterSql.countSql(w);
        String pageSql = HistoryFilterSql.pageSql(w);
        try (Connection c = connect()) {
            long total;
            try (PreparedStatement p = c.prepareStatement(countSql)) {
                bind(p, w.params());
                try (ResultSet rs = p.executeQuery()) { rs.next(); total = rs.getLong(1); }
            }
            List<Map<String,Object>> out = new ArrayList<>();
            if (total > q.offset()) {                          // skip the page query when the page is past the end
                try (PreparedStatement p = c.prepareStatement(pageSql)) {
                    bind(p, w.params());
                    int n = w.params().size();
                    p.setInt(n + 1, q.size());
                    p.setLong(n + 2, q.offset());
                    try (ResultSet rs = p.executeQuery()) {
                        while (rs.next()) out.add(row(rs));
                    }
                }
            }
            return PageResult.of(out, q.page(), q.size(), total);
        } catch (SQLException e) {
            System.err.println("Could not read deployment history: " + e.getMessage());
            throw new IllegalStateException("The history database is unavailable.", e);
        }
    }

    private static Map<String,Object> row(ResultSet rs) throws SQLException {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("environment", rs.getString("environment"));
        m.put("folder", rs.getString("folder"));
        m.put("status", rs.getString("status"));
        m.put("total", rs.getInt("total_scripts"));
        m.put("successful", rs.getInt("successful_scripts"));
        m.put("failed", rs.getInt("failed_scripts"));
        m.put("startedAt", rs.getTimestamp("started_at"));
        m.put("completedAt", rs.getTimestamp("completed_at"));
        m.put("deployedBy", rs.getString("deployed_by"));
        m.put("commitMode", rs.getString("commit_mode"));
        return m;
    }
}
