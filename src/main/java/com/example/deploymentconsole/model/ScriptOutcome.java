package com.example.deploymentconsole.model;

/**
 * What actually happened to a script's changes, derived from the stored script status, the deployment's
 * commit mode and the deployment's final status. The stored {@link ScriptStatus} is never changed; this is
 * computed on read (see {@link #of}).
 *
 * <p>Why it is needed: in commit mode {@code all} every script runs in one transaction that is committed only
 * after the last script. Scripts that ran before a failure are stored as {@code SUCCESS} but were rolled back,
 * and the same applies when the final {@code COMMIT} itself fails. Reporting them as applied would be wrong.
 */
public enum ScriptOutcome {
    /** Executed and committed: commit mode {@code script}, or commit mode {@code all} and the deployment succeeded. */
    COMMITTED,
    /** Executed without error but undone: commit mode {@code all} and the deployment did not succeed. */
    ROLLED_BACK,
    /** The script itself failed (its statements were rolled back). */
    FAILED,
    /** Never executed: SKIPPED, CANCELLED or PENDING. */
    NOT_RUN,
    /** Executing right now (the deployment is still in progress). */
    RUNNING,
    /**
     * Executed without error in commit mode {@code all} while the deployment is still PENDING/RUNNING: neither
     * committed nor rolled back yet. Becomes COMMITTED or ROLLED_BACK once the deployment finishes.
     */
    PENDING_COMMIT;

    public static final String COMMIT_MODE_SCRIPT = "script";
    public static final String COMMIT_MODE_ALL = "all";

    /**
     * The single place where the outcome rules live.
     *
     * @param scriptStatus     stored status of the script ({@code null} = unknown, treated as not run)
     * @param commitMode       {@code "script"} or {@code "all"} (case-insensitive; anything else = {@code script},
     *                         the column default)
     * @param deploymentStatus stored status of the deployment: SUCCESS, FAILED, RUNNING or PENDING (case-insensitive)
     */
    public static ScriptOutcome of(ScriptStatus scriptStatus, String commitMode, String deploymentStatus) {
        if (scriptStatus == null) return NOT_RUN;
        return switch (scriptStatus) {
            case FAILED -> FAILED;
            case RUNNING -> RUNNING;
            case PENDING, SKIPPED, CANCELLED -> NOT_RUN;
            case SUCCESS -> {
                if (!isCommitAll(commitMode)) yield COMMITTED;            // committed right after the script ran
                if ("SUCCESS".equalsIgnoreCase(deploymentStatus)) yield COMMITTED;
                if (isInProgress(deploymentStatus)) yield PENDING_COMMIT;  // final COMMIT has not happened yet
                yield ROLLED_BACK;                                         // another script or the final COMMIT failed
            }
        };
    }

    /** True if {@code commitMode} is commit-all (one transaction for the whole deployment). */
    public static boolean isCommitAll(String commitMode) {
        return COMMIT_MODE_ALL.equalsIgnoreCase(commitMode == null ? null : commitMode.trim());
    }

    private static boolean isInProgress(String deploymentStatus) {
        return "RUNNING".equalsIgnoreCase(deploymentStatus) || "PENDING".equalsIgnoreCase(deploymentStatus);
    }

    /** Parses a stored script status; {@code null} for null/unknown values instead of throwing. */
    public static ScriptStatus parseStatus(String stored) {
        if (stored == null) return null;
        try {
            return ScriptStatus.valueOf(stored.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
