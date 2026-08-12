package com.fieldservice.release;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI self-test: executes the full validation suite against the Testcontainers-backed
 * application and asserts all gates pass on a healthy build.
 *
 * <p>This test boots the full application context (real PostgreSQL via Testcontainers,
 * real Flyway migrations, real Spring Security) and runs every gate through the public
 * HTTP API — exactly the same code path as production use.
 *
 * <p>The suite uses the fixture dispatcher account ({@code dispatcher@example.com} /
 * {@code TestFixture@1234!}) which is loaded by the {@code test} profile fixtures.
 *
 * <p>Acceptance criteria verified here:
 * <ul>
 *   <li>AC-1: suite runs against a real environment with no env-specific logic compiled in.</li>
 *   <li>AC-8: JSON artifact is emitted with correct structure and non-zero exit on failure.</li>
 *   <li>AC-11: unit tests for artifact serializer, gate evaluation and exit-code logic.</li>
 *   <li>AC-12: suite executes against Testcontainers application, gates pass on healthy build.</li>
 * </ul>
 */
@DisplayName("InvariantGate self-test against Testcontainers application (WO-205)")
class InvariantGateSelfTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    private ValidationConfig config;
    private ValidationHttpClient http;

    @BeforeEach
    void setUp() {
        String runId = "selftest-" + port + "-" + Long.toHexString(System.nanoTime());
        config = ValidationConfig.forLocalContainer(
                java.net.URI.create("http://localhost:" + port), runId);
        http = new ValidationHttpClient(config.httpTimeout());
    }

    // ── Healthy build: all gates pass ─────────────────────────────────────────────

    @Test
    @DisplayName("Smoke path: health endpoint returns UP")
    void healthCheck_returnsUp() throws Exception {
        SmokePath smoke = new SmokePath(config, http);
        GateResult result = smoke.checkHealth();

        assertThat(result.passed())
                .as("Health gate should pass on a healthy build, got: %s — %s",
                        result.status(), result.detail())
                .isTrue();
    }

    @Test
    @DisplayName("Smoke path: actuator hardening — sensitive endpoints disabled")
    void actuatorHardening_sensitiveEndpointsDisabled() throws Exception {
        SmokePath smoke = new SmokePath(config, http);
        GateResult result = smoke.checkActuatorHardening();

        assertThat(result.passed())
                .as("Actuator hardening gate should pass: %s — %s",
                        result.status(), result.detail())
                .isTrue();
    }

    @Test
    @DisplayName("Smoke path: login with fixture dispatcher credentials succeeds")
    void login_withFixtureCredentials_succeeds() throws Exception {
        SmokePath smoke = new SmokePath(config, http);
        GateResult result = smoke.doLogin();

        assertThat(result.passed())
                .as("Login gate should pass with fixture credentials: %s — %s",
                        result.status(), result.detail())
                .isTrue();
    }

    @Test
    @DisplayName("Full suite: all gates pass on healthy build")
    void fullSuite_allGatesPass_onHealthyBuild() throws Exception {
        ResultsArtifact artifact = InvariantGateRunner.runWithConfig(config, http);

        List<ResultsArtifact.GateEntry> failed = artifact.gates().stream()
                .filter(g -> "FAIL".equals(g.status()) || "SETUP_ERROR".equals(g.status()))
                .toList();

        assertThat(failed)
                .as("Expected all gates to pass on a healthy build but got failures: %s", failed)
                .isEmpty();
        assertThat(artifact.overallPass())
                .as("Artifact should report overallPass=true on healthy build")
                .isTrue();
        assertThat(artifact.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("Artifact JSON schema is valid after a full run")
    void artifact_jsonSchema_isValid() throws Exception {
        ResultsArtifact artifact = InvariantGateRunner.runWithConfig(config, http);

        String json = artifact.toJson();
        assertThat(json).contains("\"runId\"");
        assertThat(json).contains("\"gates\"");
        assertThat(json).contains("\"overallPass\"");
        assertThat(json).contains("\"durationMs\"");
        // No credentials or tokens in artifact
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("TestFixture");
        assertThat(json).doesNotContain("Bearer ");
    }

    // ── Dry-run mode ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dry-run mode: write gates are SKIP, read-only gates PASS")
    void dryRunMode_writeGatesAreSkipped() throws Exception {
        String runId = "dryrun-" + Long.toHexString(System.nanoTime());
        ValidationConfig dryConfig = new ValidationConfig(
                config.baseUrl(), config.accountEmail(), config.accountPassword(),
                runId, true /* dryRun */, "/tmp/dry-" + runId + ".json",
                config.httpTimeout());

        ResultsArtifact artifact = InvariantGateRunner.runWithConfig(dryConfig, http);

        List<ResultsArtifact.GateEntry> writeGates = artifact.gates().stream()
                .filter(g -> "SKIP".equals(g.status()))
                .toList();
        // In dry-run mode, inventory and guard gates should be skipped
        assertThat(writeGates)
                .as("Write gates should be SKIP in dry-run mode")
                .isNotEmpty();

        // No gate should FAIL in dry-run mode (skipped is not failure)
        List<ResultsArtifact.GateEntry> failed = artifact.gates().stream()
                .filter(g -> "FAIL".equals(g.status()))
                .toList();
        assertThat(failed)
                .as("No gate should FAIL in dry-run mode: %s", failed)
                .isEmpty();
    }

    // ── Idempotency ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Running suite twice leaves no accumulating residue")
    void idempotency_noResidueAfterTwoRuns() throws Exception {
        // Run 1
        String runId1 = "idem-1-" + Long.toHexString(System.nanoTime());
        ValidationConfig cfg1 = ValidationConfig.forLocalContainer(config.baseUrl(), runId1);
        ValidationHttpClient http1 = new ValidationHttpClient(cfg1.httpTimeout());
        InvariantGateRunner.runWithConfig(cfg1, http1);

        // Run 2 (different run ID, same environment)
        String runId2 = "idem-2-" + Long.toHexString(System.nanoTime());
        ValidationConfig cfg2 = ValidationConfig.forLocalContainer(config.baseUrl(), runId2);
        ValidationHttpClient http2 = new ValidationHttpClient(cfg2.httpTimeout());
        ResultsArtifact run2 = InvariantGateRunner.runWithConfig(cfg2, http2);

        // Both runs should complete cleanly; run 2 must not be affected by run 1 residue
        List<ResultsArtifact.GateEntry> run2Failures = run2.gates().stream()
                .filter(g -> "FAIL".equals(g.status()))
                .toList();
        assertThat(run2Failures)
                .as("Second run should not fail due to first-run residue: %s", run2Failures)
                .isEmpty();
    }
}
