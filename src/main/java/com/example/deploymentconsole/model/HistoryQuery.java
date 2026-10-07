package com.example.deploymentconsole.model;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Filters and paging for {@code GET /api/history}.
 *
 * <p>All filters are optional ({@code null} = not filtered). {@code environment}, {@code status} and
 * {@code deployedBy} match case-insensitively; {@code from}/{@code to} are inclusive calendar dates applied to
 * {@code started_at} in the server's time zone. Build instances with {@link #of}, which validates and normalises.</p>
 */
public record HistoryQuery(
        String environment,
        String status,
        String deployedBy,
        LocalDate from,
        LocalDate to,
        int page,
        int size
) {
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 200;

    public HistoryQuery {
        if (page < 0) throw new IllegalArgumentException("page must be 0 or greater.");
        if (size < 1 || size > MAX_SIZE)
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE + ".");
        if (from != null && to != null && from.isAfter(to))
            throw new IllegalArgumentException("from (" + from + ") must not be after to (" + to + ").");
        environment = blankToNull(environment);
        status = blankToNull(status);
        if (status != null) status = status.toUpperCase(Locale.ROOT);   // statuses are stored upper-case
        deployedBy = blankToNull(deployedBy);
    }

    /** First page, default size, no filters (what {@code GET /api/history} without parameters returns). */
    public static HistoryQuery firstPage() {
        return new HistoryQuery(null, null, null, null, null, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    /** Row offset of the first row on this page (long: page * size can exceed an int). */
    public long offset() {
        return (long) page * size;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
