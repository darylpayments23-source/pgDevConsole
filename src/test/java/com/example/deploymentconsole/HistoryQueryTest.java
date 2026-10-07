package com.example.deploymentconsole;

import com.example.deploymentconsole.model.HistoryQuery;
import com.example.deploymentconsole.model.PageResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryQueryTest {
    private static HistoryQuery q(int page, int size) {
        return new HistoryQuery(null, null, null, null, null, page, size);
    }

    @Test void defaultsToFirstPageOf25() {
        var q = HistoryQuery.firstPage();
        assertEquals(0, q.page());
        assertEquals(25, q.size());
        assertEquals(0, q.offset());
    }

    @Test void rejectsInvalidPaging() {
        assertThrows(IllegalArgumentException.class, () -> q(-1, 25));
        assertThrows(IllegalArgumentException.class, () -> q(0, 0));
        var e = assertThrows(IllegalArgumentException.class, () -> q(0, 201));
        assertTrue(e.getMessage().contains("200"));
        assertDoesNotThrow(() -> q(0, 200));
        assertDoesNotThrow(() -> q(0, 1));
    }

    @Test void rejectsFromAfterTo() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryQuery(null, null, null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31), 0, 25));
        assertDoesNotThrow(() -> new HistoryQuery(null, null, null,
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31), 0, 25));
    }

    @Test void normalisesFilters() {
        var q = new HistoryQuery("  ", " failed ", "", null, null, 0, 25);
        assertNull(q.environment());
        assertEquals("FAILED", q.status());
        assertNull(q.deployedBy());
    }

    @Test void offsetDoesNotOverflow() {
        assertEquals((long) Integer.MAX_VALUE * 200, q(Integer.MAX_VALUE, 200).offset());
    }

    @Test void pageResultTotals() {
        assertEquals(54, PageResult.of(List.of(), 0, 25, 1342).totalPages());
        assertEquals(1, PageResult.of(List.of(), 0, 25, 25).totalPages());
        assertEquals(2, PageResult.of(List.of(), 0, 25, 26).totalPages());
        assertEquals(0, PageResult.of(List.of(), 0, 25, 0).totalPages());
    }
}
