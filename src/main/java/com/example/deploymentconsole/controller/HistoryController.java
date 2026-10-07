package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.config.RequireAuth;
import com.example.deploymentconsole.model.HistoryQuery;
import com.example.deploymentconsole.model.PageResult;
import com.example.deploymentconsole.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paged, filterable deployment history.
 *
 * <pre>GET /api/history?page=0&amp;size=25&amp;environment=prod&amp;status=FAILED&amp;deployedBy=alice&amp;from=2026-01-01&amp;to=2026-01-31</pre>
 *
 * Without parameters it returns the first page (25 newest deployments). Invalid parameters give
 * {@code 400 {"error":"BAD_REQUEST","message":"..."}}.
 */
@RestController
@RequestMapping("/api/history")
@RequireAuth
public class HistoryController {
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final HistoryService service;

    public HistoryController(HistoryService service) { this.service = service; }

    @GetMapping
    public PageResult<Map<String, Object>> list(
            @RequestParam(defaultValue = "" + HistoryQuery.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + HistoryQuery.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deployedBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // HistoryQuery validates page >= 0, 1 <= size <= 200 and from <= to (IllegalArgumentException -> 400).
        var q = new HistoryQuery(environment, status, deployedBy, date("from", from), date("to", to), page, size);
        return service.list(q);
    }

    /** Parses an optional ISO date (yyyy-MM-dd); blank = no filter. */
    static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a valid date in yyyy-MM-dd format (got '" + value + "').");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(400, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(400, "BAD_REQUEST", e.getName() + " must be a whole number (got '" + e.getValue() + "').");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable(IllegalStateException e) {
        log.error("History request failed: {}", e.getMessage());
        return body(503, "SERVICE_UNAVAILABLE", "The history database is unavailable. Try again later.");
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", code);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }
}
