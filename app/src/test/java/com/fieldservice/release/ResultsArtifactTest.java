package com.fieldservice.release;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResultsArtifact}: JSON serialisation, exit-code derivation,
 * and cleanup-outcome tracking — no network access.
 */
class ResultsArtifactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Exit code ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exit code is 0 when all gates pass")
    void exitCode_zero_allPass() {
        var artifact = new ResultsArtifact("run-1");
        artifact.add(GateResult.pass("SMOKE_PATH", 100));
        artifact.add(GateResult.pass("AUDIT_REVISION", 200));
        artifact.completeCleanup(true);
        assertThat(artifact.exitCode()).isZero();
        assertThat(artifact.anyFailed()).isFalse();
    }

    @Test
    @DisplayName("exit code is 1 when any gate fails")
    void exitCode_one_oneFail() {
        var artifact = new ResultsArtifact("run-2");
        artifact.add(GateResult.pass("SMOKE_PATH", 50));
        artifact.add(GateResult.fail("AUDIT_REVISION", 100, "no revision found",
                FailureClass.INVARIANT_VIOLATION));
        artifact.completeCleanup(true);
        assertThat(artifact.exitCode()).isEqualTo(1);
        assertThat(artifact.anyFailed()).isTrue();
    }

    @Test
    @DisplayName("exit code is 1 when a gate errors")
    void exitCode_one_error() {
        var artifact = new ResultsArtifact("run-3");
        artifact.add(GateResult.error("SMOKE_PATH", 0, new RuntimeException("timeout")));
        artifact.completeCleanup(false);
        assertThat(artifact.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("exit code is 2 on setup failure")
    void exitCode_two_setup() {
        var artifact = new ResultsArtifact("run-4");
        artifact.markSetupFailed("Validation account not found");
        assertThat(artifact.exitCode()).isEqualTo(2);
        assertThat(artifact.setupDidFail()).isTrue();
    }

    @Test
    @DisplayName("skipped gates do not count as failures")
    void skippedGates_notFailed() {
        var artifact = new ResultsArtifact("run-5");
        artifact.add(GateResult.skip("AUDIT_REVISION", "dry-run"));
        artifact.add(GateResult.skip("INVENTORY_INTEGRITY", "dry-run"));
        artifact.completeCleanup(true);
        assertThat(artifact.exitCode()).isZero();
        assertThat(artifact.anyFailed()).isFalse();
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON artifact contains required fields")
    void json_containsRequiredFields() throws Exception {
        var artifact = new ResultsArtifact("run-json-1");
        artifact.add(GateResult.pass("SMOKE_PATH", 42));
        artifact.add(GateResult.fail("AUDIT_REVISION", 88, "missing revision",
                FailureClass.INVARIANT_VIOLATION));
        artifact.completeCleanup(false);

        String json = artifact.toJson();
        Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});

        assertThat(parsed).containsKey("runId");
        assertThat(parsed).containsKey("startedAt");
        assertThat(parsed).containsKey("completedAt");
        assertThat(parsed).containsKey("overall");
        assertThat(parsed).containsKey("cleanupStatus");
        assertThat(parsed.get("overall")).isEqualTo("FAIL");
        assertThat(parsed.get("cleanupStatus")).isEqualTo("RESIDUE_PRESENT");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates = (List<Map<String, Object>>) parsed.get("gates");
        assertThat(gates).hasSize(2);
        assertThat(gates.get(0).get("status")).isEqualTo("PASS");
        assertThat(gates.get(1).get("status")).isEqualTo("FAIL");
        assertThat(gates.get(1).get("failureClass")).isEqualTo("INVARIANT_VIOLATION");
    }

    @Test
    @DisplayName("JSON overall is PASS when all gates pass")
    void json_overall_pass() throws Exception {
        var artifact = new ResultsArtifact("run-json-2");
        artifact.add(GateResult.pass("SMOKE_PATH", 10));
        artifact.completeCleanup(true);
        Map<String, Object> parsed = MAPPER.readValue(artifact.toJson(), new TypeReference<>() {});
        assertThat(parsed.get("overall")).isEqualTo("PASS");
        assertThat(parsed.get("cleanupStatus")).isEqualTo("CLEAN");
    }

    @Test
    @DisplayName("JSON overall is SETUP_ERROR on setup failure")
    void json_overall_setupError() throws Exception {
        var artifact = new ResultsArtifact("run-json-3");
        artifact.markSetupFailed("no account");
        Map<String, Object> parsed = MAPPER.readValue(artifact.toJson(), new TypeReference<>() {});
        assertThat(parsed.get("overall")).isEqualTo("SETUP_ERROR");
    }

    // ── Artifact file write ───────────────────────────────────────────────────

    @Test
    @DisplayName("artifact is written to file")
    void write_createsFile(@TempDir Path tmpDir) throws IOException {
        var artifact = new ResultsArtifact("run-file-1");
        artifact.add(GateResult.pass("SMOKE_PATH", 5));
        artifact.completeCleanup(true);

        Path outFile = tmpDir.resolve("gate-results.json");
        String written = artifact.write(outFile);

        assertThat(outFile).exists();
        String content = java.nio.file.Files.readString(outFile);
        assertThat(content).isEqualTo(written);
        assertThat(content).contains("run-file-1");
    }

    // ── GateResult helpers ────────────────────────────────────────────────────

    @Test
    @DisplayName("GateResult.pass() and fail() helpers set correct fields")
    void gateResult_helpers() {
        var pass = GateResult.pass("GATE_A", 123);
        assertThat(pass.passed()).isTrue();
        assertThat(pass.failed()).isFalse();
        assertThat(pass.durationMs()).isEqualTo(123);

        var fail = GateResult.fail("GATE_B", 456, "oops", FailureClass.INVARIANT_VIOLATION);
        assertThat(fail.failed()).isTrue();
        assertThat(fail.passed()).isFalse();
        assertThat(fail.failureClass()).isEqualTo(FailureClass.INVARIANT_VIOLATION);

        var skip = GateResult.skip("GATE_C", "dry-run");
        assertThat(skip.skipped()).isTrue();
        assertThat(skip.failed()).isFalse();
        assertThat(skip.durationMs()).isZero();
    }
}
