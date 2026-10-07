package com.example.deploymentconsole.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentDetailTest {

    private static final Instant START = Instant.parse("2026-10-01T08:00:00Z");

    private static DeploymentDetail.Header header(String status, String mode, Instant completed) {
        return new DeploymentDetail.Header("6f1c0a5e-1111-4222-8333-444455556666", "dev", "C:\\deploy\\release-001",
                status, mode, "alice", START, completed, null, 5, 2, 1);
    }

    private static DeploymentDetail.ScriptRow row(int order, String status, String error) {
        return new DeploymentDetail.ScriptRow(order, "00" + order + "_x/00" + order + ".sql", "00" + order + ".sql",
                order, status, "SKIPPED".equals(status) ? null : 100L, error, "ab12", false);
    }

    /** 5 scripts: 2 ok, 1 failed, 2 skipped (rows deliberately out of order). */
    private static List<DeploymentDetail.ScriptRow> failedRun() {
        return List.of(row(4, "SKIPPED", null), row(1, "SUCCESS", null), row(3, "FAILED", "ERROR: relation \"x\" does not exist"),
                row(5, "SKIPPED", null), row(2, "SUCCESS", null));
    }

    @Test void commitAllFailureRollsBackEarlierScripts() {
        var d = DeploymentDetail.of(header("FAILED", "all", START.plusMillis(8123)), failedRun());
        assertEquals(List.of(1, 2, 3, 4, 5), d.scripts().stream().map(DeploymentDetail.ScriptDetail::order).toList());
        assertEquals(List.of(ScriptOutcome.ROLLED_BACK, ScriptOutcome.ROLLED_BACK, ScriptOutcome.FAILED,
                ScriptOutcome.NOT_RUN, ScriptOutcome.NOT_RUN),
                d.scripts().stream().map(DeploymentDetail.ScriptDetail::outcome).toList());
        assertEquals(0, d.committed());
        assertEquals(2, d.rolledBack());
        assertEquals(2, d.skipped());
        assertEquals("SUCCESS", d.scripts().get(0).status(), "stored status is reported unchanged");
        assertEquals("ERROR: relation \"x\" does not exist", d.scripts().get(2).error());
        assertEquals(8123L, d.durationMs());
        assertEquals("all", d.commitMode());
    }

    @Test void commitPerScriptFailureKeepsEarlierScriptsCommitted() {
        var d = DeploymentDetail.of(header("FAILED", "script", START.plusSeconds(3)), failedRun());
        assertEquals(2, d.committed());
        assertEquals(0, d.rolledBack());
        assertEquals(2, d.skipped());
        assertEquals(ScriptOutcome.COMMITTED, d.scripts().get(0).outcome());
    }

    @Test void runningDeploymentHasNoDurationAndNothingRolledBack() {
        var rows = List.of(row(1, "SUCCESS", null), row(2, "RUNNING", null), row(3, "PENDING", null));
        var d = DeploymentDetail.of(header("RUNNING", "all", null), rows);
        assertNull(d.durationMs());
        assertEquals(List.of(ScriptOutcome.PENDING_COMMIT, ScriptOutcome.RUNNING, ScriptOutcome.NOT_RUN),
                d.scripts().stream().map(DeploymentDetail.ScriptDetail::outcome).toList());
        assertEquals(0, d.rolledBack());
        assertEquals(0, d.committed());
    }

    @Test void unknownCommitModeIsNormalisedToScript() {
        var d = DeploymentDetail.of(header("SUCCESS", null, START), List.of(row(1, "SUCCESS", null)));
        assertEquals("script", d.commitMode());
        assertEquals(1, d.committed());
    }
}
