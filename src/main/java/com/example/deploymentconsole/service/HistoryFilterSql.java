package com.example.deploymentconsole.service;

import com.example.deploymentconsole.model.HistoryQuery;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL for the paged history query. The SQL text is assembled only from the constant fragments below;
 * every user-supplied value goes into {@link Where#params()} and is bound with {@code PreparedStatement},
 * so no filter value can change the statement (no SQL injection).
 */
final class HistoryFilterSql {
    private HistoryFilterSql() {}

    /** A WHERE clause ({@code ""} when unfiltered) and its bind values, in order. */
    record Where(String sql, List<Object> params) {}

    static Where where(HistoryQuery q) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.environment() != null) {
            clauses.add("lower(environment) = lower(?)");      // uses idx_deployment_history_env_started
            params.add(q.environment());
        }
        if (q.status() != null) {
            clauses.add("status = ?");                         // HistoryQuery upper-cases; statuses are stored upper-case
            params.add(q.status());                            // uses idx_deployment_history_status_started
        }
        if (q.deployedBy() != null) {
            clauses.add("lower(deployed_by) = lower(?)");
            params.add(q.deployedBy());
        }
        // started_at is a TIMESTAMP (no zone) written in the server's zone, so compare against local midnights.
        // "to" is inclusive: everything before the following midnight.
        if (q.from() != null) {
            clauses.add("started_at >= ?");
            params.add(Timestamp.valueOf(q.from().atStartOfDay()));
        }
        if (q.to() != null) {
            clauses.add("started_at < ?");
            params.add(Timestamp.valueOf(q.to().plusDays(1).atStartOfDay()));
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    static String countSql(Where w) {
        return "SELECT COUNT(*) FROM deployment_history" + w.sql();
    }

    /** Page query; after {@code w.params()} bind LIMIT (size) and OFFSET (page * size). */
    static String pageSql(Where w) {
        return "SELECT id, environment, folder, status, total_scripts,\n"
                + "       successful_scripts, failed_scripts, started_at, completed_at, deployed_by\n"
                + "FROM deployment_history" + w.sql() + "\n"
                + "ORDER BY started_at DESC, id DESC\n"            // id breaks ties so paging is stable
                + "LIMIT ? OFFSET ?";
    }
}
