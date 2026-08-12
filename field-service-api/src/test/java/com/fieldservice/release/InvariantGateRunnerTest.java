package com.fieldservice.release;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InvariantGateRunner}: exit-code logic, argument parsing,
 * and cleanup-by-run-identifier helper.
 * No network access — gates are replaced with stubs.
 */
class InvariantGateRunnerTest {

    // ── Argument parsing ──────────────────────────────────────────────────────

    @Test
    void containsFlagReturnsTrueWhenPresent() {
        assertThat(InvariantGateRunner.containsFlag(
                new String[]{"--dry-run", "--output", "out.json"}, "--dry-run")).isTrue();
    }

    @Test
    void containsFlagReturnsFalseWhenAbsent() {
        assertThat(InvariantGateRunner.containsFlag(
                new String[]{"--output", "out.json"}, "--dry-run")).isFalse();
    }

    @Test
    void flagValueReturnsValueAfterFlag() {
        assertThat(InvariantGateRunner.flagValue(
                new String[]{"--output", "results.json"}, "--output"))
                .isEqualTo("results.json");
    }

    @Test
    void flagValueReturnsNullWhenAbsent() {
        assertThat(InvariantGateRunner.flagValue(
                new String[]{"--dry-run"}, "--output")).isNull();
    }

    // ── Exit code logic via run() ─────────────────────────────────────────────

    @Test
    void exitCodeZeroWhenAllGatesPass() {
        Gate stubPass = stubGate("gate-1", GateResult.pass("gate-1", 10));
        RunConfig config = RunConfig.forTest("http://irrelevant", true); // dry-run avoids login

        int code = InvariantGateRunner.run(config, List.of(stubPass), devNull());

        assertThat(code).isEqualTo(0);
    }

    @Test
    void exitCodeOneWhenAGateFails() {
        Gate stubFail = stubGate("gate-1", GateResult.fail("gate-1", 10, "broken"));
        RunConfig config = RunConfig.forTest("http://irrelevant", true);

        int code = InvariantGateRunner.run(config, List.of(stubFail), devNull());

        assertThat(code).isEqualTo(1);
    }

    @Test
    void exitCodeTwoWhenSetupError() {
        Gate stub = stubGate("gate-1", GateResult.setupError("gate-1", 10, "missing part"));
        RunConfig config = RunConfig.forTest("http://irrelevant", true);

        int code = InvariantGateRunner.run(config, List.of(stub), devNull());

        assertThat(code).isEqualTo(2);
    }

    @Test
    void exitCodeThreeWhenTransportError() {
        Gate stub = stubGate("gate-1", GateResult.transportError("gate-1", 10, "timeout"));
        RunConfig config = RunConfig.forTest("http://irrelevant", true);

        int code = InvariantGateRunner.run(config, List.of(stub), devNull());

        assertThat(code).isEqualTo(3);
    }

    @Test
    void allGatesRunEvenIfEarlierOneFails() {
        boolean[] ranSecond = {false};

        Gate failFirst = stubGate("gate-1", GateResult.fail("gate-1", 10, "broken"));
        Gate trackSecond = new Gate() {
            @Override public String name() { return "gate-2"; }
            @Override public GateResult run(RunConfig c, ApiClient a) {
                ranSecond[0] = true;
                return GateResult.pass("gate-2", 5);
            }
        };

        RunConfig config = RunConfig.forTest("http://irrelevant", true);
        InvariantGateRunner.run(config, List.of(failFirst, trackSecond), devNull());

        assertThat(ranSecond[0]).isTrue();
    }

    @Test
    void gateExceptionCapturedAsFail() {
        Gate throwing = new Gate() {
            @Override public String name() { return "exploding-gate"; }
            @Override public GateResult run(RunConfig c, ApiClient a) {
                throw new RuntimeException("unexpected boom");
            }
        };

        RunConfig config = RunConfig.forTest("http://irrelevant", true);
        int code = InvariantGateRunner.run(config, List.of(throwing), devNull());

        assertThat(code).isEqualTo(1);
    }

    @Test
    void dryRunSkipsWriteGates() {
        boolean[] ranWrite = {false};

        Gate writeGate = new Gate() {
            @Override public String name() { return "write-gate"; }
            @Override public GateResult run(RunConfig c, ApiClient a) {
                if (c.dryRun()) return GateResult.skip(name(), "dry-run");
                ranWrite[0] = true;
                return GateResult.pass(name(), 10);
            }
        };

        RunConfig config = RunConfig.forTest("http://irrelevant", true);
        int code = InvariantGateRunner.run(config, List.of(writeGate), devNull());

        assertThat(ranWrite[0]).isFalse();
        assertThat(code).isEqualTo(0); // SKIP is not a failure
    }

    // ── Cleanup helper ────────────────────────────────────────────────────────

    @Test
    void cleanupReturnsSkippedInDryRunMode() {
        RunConfig config = RunConfig.forTest("http://irrelevant", true);
        ResultsArtifact.CleanupOutcome outcome =
                InvariantGateRunner.runCleanup(config, ApiClient.create(), List.of());

        assertThat(outcome).isEqualTo(ResultsArtifact.CleanupOutcome.SKIPPED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Gate stubGate(String name, GateResult result) {
        return new Gate() {
            @Override public String name() { return name; }
            @Override public GateResult run(RunConfig c, ApiClient a) { return result; }
        };
    }

    private static PrintWriter devNull() {
        return new PrintWriter(new StringWriter());
    }
}
