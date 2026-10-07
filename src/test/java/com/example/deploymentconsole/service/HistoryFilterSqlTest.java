package com.example.deploymentconsole.service;

import com.example.deploymentconsole.model.HistoryQuery;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryFilterSqlTest {

    @Test void noFiltersMeansNoWhereClause() {
        var w = HistoryFilterSql.where(HistoryQuery.firstPage());
        assertEquals("", w.sql());
        assertTrue(w.params().isEmpty());
        assertTrue(HistoryFilterSql.pageSql(w).endsWith("LIMIT ? OFFSET ?"));
    }

    @Test void allFiltersCombineWithBindParameters() {
        var q = new HistoryQuery("prod", "failed", "alice",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 2, 50);
        var w = HistoryFilterSql.where(q);
        assertEquals(" WHERE lower(environment) = lower(?) AND status = ? AND lower(deployed_by) = lower(?)"
                + " AND started_at >= ? AND started_at < ?", w.sql());
        assertEquals(List.of("prod", "FAILED", "alice",
                Timestamp.valueOf("2026-01-01 00:00:00"),
                Timestamp.valueOf("2026-02-01 00:00:00")), w.params());   // "to" is inclusive -> next midnight
        assertEquals(100, q.offset());
    }

    @Test void userInputNeverReachesSqlText() {
        String evil = "x' OR '1'='1'; DROP TABLE deployment_history; --";
        var w = HistoryFilterSql.where(new HistoryQuery(evil, evil, evil, null, null, 0, 25));
        assertFalse(w.sql().contains(evil));
        assertFalse(HistoryFilterSql.countSql(w).contains("DROP"));
        assertFalse(HistoryFilterSql.pageSql(w).contains("DROP"));
        assertEquals(3, w.params().size());
        assertEquals(evil, w.params().get(0));
    }
}
