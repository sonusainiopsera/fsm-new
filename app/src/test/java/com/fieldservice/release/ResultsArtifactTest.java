package com.fieldservice.release;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResultsArtifact} — no network access.
 *
 * <p>Covers:
 * <ul>
 *   <li>JSON schema: required fields present, ISO-8601 timestamps, gate entries.</li>
 *   <li>Exit code: 0 when all gates pass, 1 when any gate fails.</li>
 *   <li>overallPass: false when any gate has FAIL or SETUP_ERROR status.</li>
 *   <li>File write: artifact is written to disk and parseable.</li>
 *   <li>Human-readable summary: printed correctly.</li>
 * </ul>
 */
@DisplayName("ResultsArtifact unit tests")
class ResultsArtifactTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:01:00Z");

    @Test
    @DisplayName("exitCode returns 0 when all gates pass")
    void exitCode_allPass_returnsZero() {
        List<GateResult> results = List.of(
                GateResult.pass("GateA", Duration.ofMillis(100), "ok"),
                GateResult.pass("GateB", Duration.ofMillis(200), "ok")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-001", "https://test.example.com", T0, T1, false, results);

        assertThat(artifact.overallPass()).isTrue();
        assertThat(artifact.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("exitCode returns 1 when any gate fails")
    void exitCode_anyFail_returnsOne() {
        List<GateResult> results = List.of(
                GateResult.pass("GateA", Duration.ofMillis(100), "ok"),
                GateResult.fail("GateB", Duration.ofMillis(200), "invariant violated")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-002", "https://test.example.com", T0, T1, false, results);

        assertThat(artifact.overallPass()).isFalse();
        assertThat(artifact.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("exitCode returns 1 when any gate has SETUP_ERROR")
    void exitCode_setupError_returnsOne() {
        List<GateResult> results = List.of(
                GateResult.setupError("GateA", "missing env var"),
                GateResult.pass("GateB", Duration.ofMillis(100), "ok")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-003", "https://test.example.com", T0, T1, false, results);

        assertThat(artifact.overallPass()).isFalse();
        assertThat(artifact.exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKIP gates do not affect overallPass")
    void skipGates_doNotAffectOverallPass() {
        List<GateResult> results = List.of(
                GateResult.pass("GateA", Duration.ofMillis(100), "ok"),
                GateResult.skip("GateB", "dry-run mode")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-004", "https://test.example.com", T0, T1, true, results);

        assertThat(artifact.overallPass()).isTrue();
        assertThat(artifact.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("JSON artifact contains required top-level fields")
    void toJson_containsRequiredFields() throws IOException {
        List<GateResult> results = List.of(
                GateResult.pass("AuditGate", Duration.ofMillis(150), "revision found")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-005", "https://env.example.com", T0, T1, false, results);

        String json = artifact.toJson();
        JsonNode node = ValidationHttpClient.MAPPER.readTree(json);

        assertThat(node.has("runId")).isTrue();
        assertThat(node.get("runId").asText()).isEqualTo("run-005");
        assertThat(node.has("environment")).isTrue();
        assertThat(node.has("startedAt")).isTrue();
        assertThat(node.has("finishedAt")).isTrue();
        assertThat(node.has("durationMs")).isTrue();
        assertThat(node.has("overallPass")).isTrue();
        assertThat(node.has("dryRun")).isTrue();
        assertThat(node.has("gates")).isTrue();
        assertThat(node.get("gates").isArray()).isTrue();
    }

    @Test
    @DisplayName("JSON gate entries have required fields")
    void toJson_gateEntries_haveRequiredFields() throws IOException {
        List<GateResult> results = List.of(
                GateResult.fail("InventoryGate", Duration.ofMillis(250), "balance went negative")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-006", "https://env.example.com", T0, T1, false, results);

        String json = artifact.toJson();
        JsonNode node = ValidationHttpClient.MAPPER.readTree(json);
        JsonNode gate = node.get("gates").get(0);

        assertThat(gate.get("gate").asText()).isEqualTo("InventoryGate");
        assertThat(gate.get("status").asText()).isEqualTo("FAIL");
        assertThat(gate.has("durationMs")).isTrue();
        assertThat(gate.get("detail").asText()).isEqualTo("balance went negative");
    }

    @Test
    @DisplayName("writeTo persists readable JSON file")
    void writeTo_persistsReadableJson(@TempDir Path tempDir) throws IOException {
        Path dest = tempDir.resolve("subdir").resolve("results.json");
        List<GateResult> results = List.of(
                GateResult.pass("GateA", Duration.ofMillis(100), "ok")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-007", "https://test.example.com", T0, T1, false, results);

        artifact.writeTo(dest);

        assertThat(Files.exists(dest)).isTrue();
        String content = Files.readString(dest);
        JsonNode node = ValidationHttpClient.MAPPER.readTree(content);
        assertThat(node.get("runId").asText()).isEqualTo("run-007");
    }

    @Test
    @DisplayName("printSummary includes run ID, overall result and gate names")
    void printSummary_includesKeyInfo() {
        List<GateResult> results = List.of(
                GateResult.pass("Gate1", Duration.ofMillis(100), "ok"),
                GateResult.fail("Gate2", Duration.ofMillis(200), "violated")
        );
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-008", "https://env.example.com", T0, T1, false, results);

        StringWriter sw = new StringWriter();
        artifact.printSummary(new PrintWriter(sw));
        String summary = sw.toString();

        assertThat(summary).contains("run-008");
        assertThat(summary).contains("FAIL");
        assertThat(summary).contains("Gate1");
        assertThat(summary).contains("Gate2");
        assertThat(summary).contains("violated");
    }

    @Test
    @DisplayName("durationMs equals duration between startedAt and finishedAt")
    void durationMs_matchesInterval() {
        Instant from = Instant.parse("2026-01-01T12:00:00Z");
        Instant to   = Instant.parse("2026-01-01T12:00:02.500Z");
        ResultsArtifact artifact = ResultsArtifact.of(
                "run-009", "https://env.example.com", from, to, false, List.of());

        assertThat(artifact.durationMs()).isEqualTo(2500L);
    }
}
