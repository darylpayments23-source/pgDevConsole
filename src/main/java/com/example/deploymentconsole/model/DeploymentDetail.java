package com.example.deploymentconsole.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * One deployment with every script that ran under it, as returned by {@code GET /api/history/{id}}.
 *
 * <p>{@code total}, {@code successful} and {@code failed} are the stored header counts (same as the list).
 * {@code committed}, {@code rolledBack} and {@code skipped} are derived from each script's {@link ScriptOutcome}.
 */
public record DeploymentDetail(
        String id,
        String environment,
        String folder,
        String status,
        String commitMode,
        String deployedBy,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String error,
        int total,
        int successful,
        int failed,
        int committed,
        int rolledBack,
        int skipped,
        List<ScriptDetail> scripts
) {
    /** One script row of the deployment, with its derived outcome. */
    public record ScriptDetail(
            int order,
            String path,
            String filename,
            long sequence,
            String status,          // stored status, unchanged
            ScriptOutcome outcome,  // derived, see ScriptOutcome.of
            Long durationMs,
            String error,
            String checksum,
            boolean checksumChanged
    ) {}

    /** Script row as read from {@code deployment_script_history}, before the outcome is derived. */
    public record ScriptRow(int order, String path, String filename, long sequence, String status,
                            Long durationMs, String error, String checksum, boolean checksumChanged) {}

    /** Header columns as read from {@code deployment_history}. */
    public record Header(String id, String environment, String folder, String status, String commitMode,
                         String deployedBy, Instant startedAt, Instant completedAt, String error,
                         int total, int successful, int failed) {}

    /**
     * Builds the detail: sorts scripts by {@code script_order}, derives each outcome and the
     * committed / rolledBack / skipped counts from the same rules.
     */
    public static DeploymentDetail of(Header h, List<ScriptRow> rows) {
        String commitMode = ScriptOutcome.isCommitAll(h.commitMode())
                ? ScriptOutcome.COMMIT_MODE_ALL : ScriptOutcome.COMMIT_MODE_SCRIPT;
        List<ScriptDetail> scripts = rows.stream()
                .sorted(Comparator.comparingInt(ScriptRow::order))
                .map(r -> new ScriptDetail(r.order(), r.path(), r.filename(), r.sequence(), r.status(),
                        ScriptOutcome.of(ScriptOutcome.parseStatus(r.status()), commitMode, h.status()),
                        r.durationMs(), r.error(), r.checksum(), r.checksumChanged()))
                .toList();
        int committed = count(scripts, ScriptOutcome.COMMITTED);
        int rolledBack = count(scripts, ScriptOutcome.ROLLED_BACK);
        int skipped = count(scripts, ScriptOutcome.NOT_RUN);
        Long duration = h.startedAt() != null && h.completedAt() != null
                ? Math.max(0, Duration.between(h.startedAt(), h.completedAt()).toMillis()) : null;
        return new DeploymentDetail(h.id(), h.environment(), h.folder(), h.status(), commitMode, h.deployedBy(),
                h.startedAt(), h.completedAt(), duration, h.error(),
                h.total(), h.successful(), h.failed(), committed, rolledBack, skipped, scripts);
    }

    private static int count(List<ScriptDetail> scripts, ScriptOutcome o) {
        return (int) scripts.stream().filter(s -> s.outcome() == o).count();
    }
}
