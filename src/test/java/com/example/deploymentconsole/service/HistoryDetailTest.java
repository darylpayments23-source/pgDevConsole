package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HistoryDetailTest {

    // No database configured: a non-UUID id must be rejected before any connection is attempted.
    private final HistoryService service = new HistoryService(new AppProperties());

    @Test void invalidIdIsEmptyWithoutTouchingTheDatabase() {
        assertTrue(service.detail("not-a-uuid").isEmpty());
        assertTrue(service.detail("").isEmpty());
        assertTrue(service.detail(null).isEmpty());
        assertTrue(service.detail("1; DROP TABLE deployment_history").isEmpty());
    }

    @Test void detailQueriesAreParameterizedAndOrdered() {
        assertTrue(HistoryService.DETAIL_HEADER_SQL.contains("WHERE id = ?"));
        assertTrue(HistoryService.DETAIL_SCRIPTS_SQL.contains("WHERE deployment_id = ?"));
        assertTrue(HistoryService.DETAIL_SCRIPTS_SQL.trim().endsWith("ORDER BY script_order"));
    }

    @Test void listQueryIncludesCommitMode() {
        var w = HistoryFilterSql.where(com.example.deploymentconsole.model.HistoryQuery.firstPage());
        assertTrue(HistoryFilterSql.pageSql(w).contains("commit_mode"));
    }
}
