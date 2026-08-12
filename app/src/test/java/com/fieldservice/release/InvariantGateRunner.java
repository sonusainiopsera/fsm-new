package com.fieldservice.release;

import com.fieldservice.release.gates.AuditRevisionGate;
import com.fieldservice.release.gates.DenyByDefaultGate;
import com.fieldservice.release.gates.GuardNeverFailsOpenGate;
import com.fieldservice.release.gates.InventoryIntegrityGate;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the post-deploy invariant validation suite.
 *
 * <p>Invocation:
 * <pre>
 *   java -cp validation.jar com.fieldservice.release.InvariantGateRunner
 * </pre>
 *
 * <p>Required environment variables — see {@link ValidationConfig} for full list:
 * <pre>
 *   VALIDATION_BASE_URL          – e.g. https://api.dev.example.com
 *   VALIDATION_ACCOUNT_EMAIL     – validation account login
 *   VALIDATION_ACCOUNT_PASSWORD  – (from secrets store)
 *   VALIDATION_RUN_ID            – git SHA + timestamp, e.g. abc1234-20260101T120000Z
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — all gates passed</li>
 *   <li>1 — one or more gates failed or setup errors occurred</li>
 *   <li>2 — configuration error (missing required env vars)</li>
 * </ul>
 *
 * <p>Idempotency: all created resources are tagged with the run identifier and cancelled
 * at the end of the run regardless of gate outcomes. A repeated run against the same
 * environment leaves no accumulating residue.
 *
 * <p>Scope boundary: this suite deliberately does NOT validate dashboard first-meaningful-
 * render times or KPI staleness. Those concerns are outside the invariant gate scope
 * and are validated by a separate frontend smoke suite.
 */
public final class InvariantGateRunner {

    public static void main(String[] args) {
        System.exit(new InvariantGateRunner().run());
    }

    /** Runs all gates and returns the process exit code. */
    public int run() {
        PrintWriter out = new PrintWriter(System.out, true);

        ValidationConfig config;
        try {
            config = ValidationConfig.fromEnvironment();
        } catch (IllegalStateException e) {
            out.println("[SETUP_ERROR] " + e.getMessage());
            return 2;
        }

        out.printf("Post-deploy validation starting%n  run=%s  env=%s  dryRun=%s%n",
                config.runId(), config.baseUrlString(), config.dryRun());

        ValidationHttpClient http = new ValidationHttpClient(config.httpTimeout());
        SmokePath smoke = new SmokePath(config, http);
        List<GateResult> results = new ArrayList<>();
        Instant startedAt = Instant.now();

        try {
            // ── Smoke path (includes login, health, actuator hardening) ──────────────
            List<GateResult> smokeResults = smoke.run();
            results.addAll(smokeResults);

            // ── Invariant gates ───────────────────────────────────────────────────────
            String createdWoId = smoke.getCreatedWorkOrderId();

            // Gate 1: Audit revision
            AuditRevisionGate auditGate = new AuditRevisionGate(config, http);
            results.add(auditGate.run(createdWoId, "ASSIGNED"));

            // Gate 2: Inventory integrity (read-write; skipped in dry-run)
            InventoryIntegrityGate inventoryGate = new InventoryIntegrityGate(config, http);
            results.add(inventoryGate.run());

            // Gate 3: Guards never fail open
            // Fetch current version of the work order
            String currentVersion = fetchCurrentVersion(http, config, createdWoId);
            GuardNeverFailsOpenGate guardGate = new GuardNeverFailsOpenGate(config, http);
            results.add(guardGate.run(createdWoId, currentVersion, null, null));

            // Gate 4: Deny-by-default
            // Probe the created work order with no token and then with a customer token
            String resourceUrl = config.baseUrlString() +
                    "/api/v1/work-orders/" + (createdWoId != null ? createdWoId : "unknown");
            DenyByDefaultGate denyGate = new DenyByDefaultGate(config, http);
            results.add(denyGate.run(resourceUrl, null /* cross-role token not available here */));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            results.add(GateResult.setupError("InvariantGateRunner",
                    "Runner interrupted: " + e.getMessage()));
        } finally {
            // ── Cleanup — always runs regardless of gate outcomes ─────────────────────
            try {
                GateResult cleanupResult = smoke.cleanup();
                results.add(cleanupResult);
                if (cleanupResult.failed()) {
                    out.println("[CLEANUP_FAILURE] " + cleanupResult.detail() +
                            " — manual cleanup may be required for run=" + config.runId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.println("[CLEANUP_FAILURE] Cleanup interrupted — manual cleanup required");
            }
        }

        // ── Artifact ─────────────────────────────────────────────────────────────────
        Instant finishedAt = Instant.now();
        ResultsArtifact artifact = ResultsArtifact.of(
                config.runId(), config.baseUrlString(),
                startedAt, finishedAt, config.dryRun(), results);

        artifact.printSummary(out);

        try {
            artifact.writeTo(Path.of(config.artifactPath()));
            out.println("Artifact written to: " + config.artifactPath());
        } catch (IOException e) {
            out.println("[WARN] Could not write artifact: " + e.getMessage());
        }

        return artifact.exitCode();
    }

    // ── Package-visible for self-test ─────────────────────────────────────────────

    /**
     * Runs all gates against the provided http client and returns the artifact.
     * Used by {@link InvariantGateSelfTest} to exercise the full runner in CI.
     */
    static ResultsArtifact runWithConfig(ValidationConfig config, ValidationHttpClient http)
            throws InterruptedException {
        SmokePath smoke = new SmokePath(config, http);
        List<GateResult> results = new ArrayList<>();
        Instant startedAt = Instant.now();

        try {
            results.addAll(smoke.run());
            String createdWoId = smoke.getCreatedWorkOrderId();

            AuditRevisionGate auditGate = new AuditRevisionGate(config, http);
            results.add(auditGate.run(createdWoId, "ASSIGNED"));

            InventoryIntegrityGate inventoryGate = new InventoryIntegrityGate(config, http);
            results.add(inventoryGate.run());

            String currentVersion = fetchCurrentVersion(http, config, createdWoId);
            GuardNeverFailsOpenGate guardGate = new GuardNeverFailsOpenGate(config, http);
            results.add(guardGate.run(createdWoId, currentVersion, null, null));

            String resourceUrl = config.baseUrlString() +
                    "/api/v1/work-orders/" + (createdWoId != null ? createdWoId : "unknown");
            DenyByDefaultGate denyGate = new DenyByDefaultGate(config, http);
            results.add(denyGate.run(resourceUrl, null));

        } finally {
            try {
                results.add(smoke.cleanup());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Instant finishedAt = Instant.now();
        return ResultsArtifact.of(config.runId(), config.baseUrlString(),
                startedAt, finishedAt, config.dryRun(), results);
    }

    private static String fetchCurrentVersion(
            ValidationHttpClient http, ValidationConfig config, String workOrderId) {
        if (workOrderId == null || workOrderId.isBlank()) return "1";
        try {
            ValidationHttpClient.ApiResponse resp = http.get(
                    config.baseUrlString() + "/api/v1/work-orders/" + workOrderId);
            if (resp.isOk()) {
                return resp.jsonField("/version");
            }
        } catch (IOException | InterruptedException ignored) { /* fall through */ }
        return "1";
    }
}
