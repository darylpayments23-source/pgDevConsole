package com.example.deploymentconsole.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.example.deploymentconsole.model.ScriptOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every script status x commit mode x deployment status combination. */
class ScriptOutcomeTest {

    private static final String[] MODES = {"script", "all"};
    private static final String[] DEPLOYMENT_STATUSES = {"SUCCESS", "FAILED", "RUNNING", "PENDING"};

    /** The expected outcome, written out as a table independent of the implementation. */
    private static ScriptOutcome expected(ScriptStatus s, String mode, String dep) {
        return switch (s) {
            case FAILED -> FAILED;
            case RUNNING -> RUNNING;
            case SKIPPED, CANCELLED, PENDING -> NOT_RUN;
            case SUCCESS -> mode.equals("script") ? COMMITTED
                    : dep.equals("SUCCESS") ? COMMITTED
                    : dep.equals("FAILED") ? ROLLED_BACK
                    : PENDING_COMMIT;   // RUNNING / PENDING: final commit not reached yet
        };
    }

    static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (ScriptStatus s : ScriptStatus.values())
            for (String mode : MODES)
                for (String dep : DEPLOYMENT_STATUSES)
                    out.add(Arguments.of(s, mode, dep, expected(s, mode, dep)));
        return out.stream();
    }

    @ParameterizedTest(name = "{0} / commit={1} / deployment={2} -> {3}")
    @MethodSource("matrix")
    void everyCombination(ScriptStatus s, String mode, String dep, ScriptOutcome want) {
        assertEquals(want, ScriptOutcome.of(s, mode, dep));
    }

    @Test void matrixCoversEveryStatus() {
        assertEquals(ScriptStatus.values().length * MODES.length * DEPLOYMENT_STATUSES.length, matrix().count());
    }

    // --- The acceptance-criteria cases spelled out ---------------------------------------------------------

    @Test void commitAllScriptsBeforeAFailureAreRolledBackNotCommitted() {
        assertEquals(ROLLED_BACK, ScriptOutcome.of(ScriptStatus.SUCCESS, "all", "FAILED"));
    }

    @Test void commitAllFailedFinalCommitRollsBackEveryScript() {
        // Every script SUCCESS but the final COMMIT failed -> deployment FAILED -> nothing applied.
        assertEquals(ROLLED_BACK, ScriptOutcome.of(ScriptStatus.SUCCESS, "all", "FAILED"));
    }

    @Test void commitPerScriptScriptsBeforeAFailureAreCommitted() {
        assertEquals(COMMITTED, ScriptOutcome.of(ScriptStatus.SUCCESS, "script", "FAILED"));
    }

    @Test void skippedAfterAFailureIsNotRun() {
        assertEquals(NOT_RUN, ScriptOutcome.of(ScriptStatus.SKIPPED, "all", "FAILED"));
        assertEquals(NOT_RUN, ScriptOutcome.of(ScriptStatus.SKIPPED, "script", "FAILED"));
    }

    @Test void successfulCommitAllDeploymentIsCommitted() {
        assertEquals(COMMITTED, ScriptOutcome.of(ScriptStatus.SUCCESS, "all", "SUCCESS"));
    }

    // --- Robustness ------------------------------------------------------------------------------------------

    @Test void commitModeAndDeploymentStatusAreCaseInsensitive() {
        assertEquals(ROLLED_BACK, ScriptOutcome.of(ScriptStatus.SUCCESS, "ALL", "failed"));
        assertEquals(COMMITTED, ScriptOutcome.of(ScriptStatus.SUCCESS, " All ", "success"));
    }

    @Test void unknownOrMissingCommitModeMeansPerScript() {
        // commit_mode defaults to 'script' in the schema.
        assertEquals(COMMITTED, ScriptOutcome.of(ScriptStatus.SUCCESS, null, "FAILED"));
        assertEquals(COMMITTED, ScriptOutcome.of(ScriptStatus.SUCCESS, "", "FAILED"));
    }

    @Test void unknownScriptStatusIsNotRun() {
        assertEquals(NOT_RUN, ScriptOutcome.of(null, "all", "SUCCESS"));
        assertNull(ScriptOutcome.parseStatus("BOGUS"));
        assertNull(ScriptOutcome.parseStatus(null));
        assertEquals(ScriptStatus.SUCCESS, ScriptOutcome.parseStatus("success"));
    }
}
