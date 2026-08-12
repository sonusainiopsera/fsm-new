package com.fieldservice.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.release.ResultsArtifact.CleanupOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResultsArtifact} serialization, exit code logic, and summary formatting.
 * No network access.
 */
class ResultsArtifactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunConfig testConfig() {
        return RunConfig.forTest("https://api.example.test", false);
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    void allPassProducesOverallPassTrue() {
        List<GateResult> gates = List.of(
                GateResult.pass("smoke-path", 100),
                GateResult.pass("audit-revision-gate", 200)
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        assertThat(artifact.overallPass()).isTrue();
        assertThat(artifact.exitCode()).isEqualTo(0);
    }

    @Test
    void oneFailMeansOverallFail() {
        List<GateResult> gates = List.of(
                GateResult.pass("smoke-path", 100),
                GateResult.fail("audit-revision-gate", 200, "Missing revision")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        assertThat(artifact.overallPass()).isFalse();
        assertThat(artifact.exitCode()).isEqualTo(1);
    }

    @Test
    void setupErrorDoesNotCountAsGateFailureButExitsTwo() {
        List<GateResult> gates = List.of(
                GateResult.setupError("inventory-integrity-gate", 50, "Part not found")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        assertThat(artifact.overallPass()).isTrue(); // setup error is not a gate failure
        assertThat(artifact.exitCode()).isEqualTo(2);
    }

    @Test
    void transportErrorExitsThree() {
        List<GateResult> gates = List.of(
                GateResult.transportError("smoke-path", 0, "Connection refused")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        assertThat(artifact.overallPass()).isTrue();
        assertThat(artifact.exitCode()).isEqualTo(3);
    }

    @Test
    void skippedGateNotCountedAsFailure() {
        List<GateResult> gates = List.of(
                GateResult.pass("smoke-path", 100),
                GateResult.skip("audit-revision-gate", "dry-run mode")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        assertThat(artifact.overallPass()).isTrue();
        assertThat(artifact.exitCode()).isEqualTo(0);
    }

    @Test
    void transportErrorTakesPrecedenceOverSetupErrorInExitCode() {
        List<GateResult> gates = List.of(
                GateResult.setupError("gate-a", 50, "Missing part"),
                GateResult.transportError("gate-b", 100, "Timeout")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        // Transport error (exit 3) takes precedence over setup error (exit 2)
        assertThat(artifact.exitCode()).isEqualTo(3);
    }

    // ── JSON schema ───────────────────────────────────────────────────────────

    @Test
    void serializedArtifactContainsRequiredFields() throws Exception {
        List<GateResult> gates = List.of(
                GateResult.pass("smoke-path", 123),
                GateResult.fail("audit-revision-gate", 456, "No revision found")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        String json = MAPPER.writeValueAsString(artifact);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.has("runId")).isTrue();
        assertThat(node.has("timestamp")).isTrue();
        assertThat(node.has("baseUrl")).isTrue();
        assertThat(node.has("dryRun")).isTrue();
        assertThat(node.has("overallPass")).isTrue();
        assertThat(node.has("gates")).isTrue();
        assertThat(node.get("gates").isArray()).isTrue();
        assertThat(node.get("gates").size()).isEqualTo(2);
    }

    @Test
    void nullFailureDetailOmittedFromPassingGateJson() throws Exception {
        GateResult passing = GateResult.pass("smoke-path", 100);
        String json = MAPPER.writeValueAsString(passing);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.has("failureDetail")).isFalse();
    }

    @Test
    void failureDetailPresentInFailingGateJson() throws Exception {
        GateResult failing = GateResult.fail("audit-revision-gate", 200, "No revision");
        String json = MAPPER.writeValueAsString(failing);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.get("failureDetail").asText()).isEqualTo("No revision");
    }

    @Test
    void artifactDoesNotContainBaseUrlPassword() throws Exception {
        List<GateResult> gates = List.of(GateResult.pass("smoke-path", 10));
        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        String json = MAPPER.writeValueAsString(artifact);

        // Password must never appear in the artifact
        assertThat(json).doesNotContain("test-password");
        assertThat(json).doesNotContain("admin-password");
    }

    // ── File output ───────────────────────────────────────────────────────────

    @Test
    void writesToFileSuccessfully(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("results.json");
        List<GateResult> gates = List.of(GateResult.pass("smoke-path", 50));
        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());

        artifact.writeTo(output.toString(), MAPPER);

        assertThat(Files.exists(output)).isTrue();
        JsonNode node = MAPPER.readTree(output.toFile());
        assertThat(node.get("overallPass").asBoolean()).isTrue();
    }

    // ── Human-readable summary ────────────────────────────────────────────────

    @Test
    void summaryContainsGateNamesAndStatuses() {
        List<GateResult> gates = List.of(
                GateResult.pass("smoke-path", 100),
                GateResult.fail("audit-revision-gate", 200, "Missing revision"),
                GateResult.skip("inventory-integrity-gate", "dry-run")
        );

        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        StringWriter sw = new StringWriter();
        artifact.printSummary(new PrintWriter(sw));
        String summary = sw.toString();

        assertThat(summary).contains("smoke-path");
        assertThat(summary).contains("PASS");
        assertThat(summary).contains("audit-revision-gate");
        assertThat(summary).contains("FAIL");
        assertThat(summary).contains("Missing revision");
        assertThat(summary).contains("SKIP");
        assertThat(summary).contains("OVERALL");
    }

    @Test
    void summaryShowsOverallPassOnAllPass() {
        List<GateResult> gates = List.of(GateResult.pass("smoke-path", 50));
        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        StringWriter sw = new StringWriter();
        artifact.printSummary(new PrintWriter(sw));

        assertThat(sw.toString()).contains("OVERALL  : PASS");
    }

    @Test
    void summaryShowsFailCountOnFailure() {
        List<GateResult> gates = List.of(
                GateResult.fail("gate-a", 100, "broke"),
                GateResult.fail("gate-b", 100, "broke again")
        );
        ResultsArtifact artifact = ResultsArtifact.of(testConfig(), gates, CleanupOutcome.pass());
        StringWriter sw = new StringWriter();
        artifact.printSummary(new PrintWriter(sw));

        assertThat(sw.toString()).contains("2 gate failures");
    }
}
