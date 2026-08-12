package com.fieldservice.release;

import com.fieldservice.release.gates.AuditRevisionGate;
import com.fieldservice.release.gates.DenyByDefaultGate;
import com.fieldservice.release.gates.GuardNeverFailsOpenGate;
import com.fieldservice.release.gates.InventoryIntegrityGate;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Post-deploy invariant gate runner.
 *
 * <h3>Usage (standalone)</h3>
 * <pre>
 *   GATE_BASE_URL=https://app.example.com \
 *   GATE_USERNAME=svc-validation@example.com \
 *   GATE_PASSWORD=... \
 *   mvn exec:java -pl app -Dexec.mainClass=com.fieldservice.release.InvariantGateRunner \
 *                         -Dexec.classpathScope=test
 * </pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 — all gates passed.</li>
 *   <li>1 — one or more invariant or smoke-path gates failed.</li>
 *   <li>2 — environment setup failure (missing account, depleted stock, etc.).</li>
 * </ul>
 *
 * <p>Note: dashboard freshness and KPI-staleness gating are explicitly out of scope for
 * this runner; they are validated by a separate rendering pipeline.
 */
public final class InvariantGateRunner {

    private InvariantGateRunner() {}

    /** Entry point for standalone execution. */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : null;
        RunConfig config = RunConfig.fromEnvironment(baseUrl != null ? baseUrl : "http://localhost:8080");
        ResultsArtifact artifact = execute(config);
        artifact.printSummary();
        try {
            artifact.write(Path.of(config.outputPath()));
            System.out.println("Artifact written to: " + config.outputPath());
        } catch (IOException e) {
            System.err.println("WARNING: failed to write artifact to " + config.outputPath() + ": " + e.getMessage());
        }
        System.exit(artifact.exitCode());
    }

    /**
     * Executes the full gate suite and returns the populated artifact.
     * Does not call {@link System#exit} — callers determine exit behaviour.
     */
    public static ResultsArtifact execute(RunConfig config) {
        ResultsArtifact artifact = new ResultsArtifact(config.runId());
        List<String> createdIds  = new ArrayList<>();

        try (HttpGateClient client = new HttpGateClient(config.baseUrl())) {

            // ── Authenticate primary account ──────────────────────────────
            try {
                client.authenticate(config.username(), config.password());
            } catch (HttpGateClient.GateException e) {
                artifact.markSetupFailed("Primary account login failed: " + e.getMessage());
                return artifact;
            }

            // ── Authenticate probe account ────────────────────────────────
            String probeToken = null;
            HttpGateClient probeClient = new HttpGateClient(config.baseUrl());
            try {
                probeToken = probeClient.authenticate(config.probeUsername(), config.probePassword());
            } catch (HttpGateClient.GateException e) {
                // Non-fatal: DenyByDefault gate will be skipped if probe token is absent
                System.err.println("WARNING: probe account login failed — DenyByDefault gate will report ERROR: "
                        + e.getMessage());
            }

            if (config.dryRun()) {
                System.out.println("DRY-RUN mode: executing read-only gates only.");
            }

            // ── Smoke path ────────────────────────────────────────────────
            GateResult smoke = SmokePath.run(client, config, createdIds);
            artifact.add(smoke);
            if (smoke.failed() && smoke.failureClass() == FailureClass.ENVIRONMENT_SETUP) {
                artifact.markSetupFailed("Smoke path setup error: " + smoke.detail());
                performCleanup(client, createdIds);
                artifact.completeCleanup(true);
                return artifact;
            }

            // ── Write gates (skipped in dry-run mode) ─────────────────────
            if (config.dryRun()) {
                artifact.add(GateResult.skip(AuditRevisionGate.GATE_NAME,   "dry-run: write gates suppressed"));
                artifact.add(GateResult.skip(InventoryIntegrityGate.GATE_NAME, "dry-run: write gates suppressed"));
                artifact.add(GateResult.skip(GuardNeverFailsOpenGate.GATE_NAME, "dry-run: write gates suppressed"));
                artifact.add(GateResult.skip(DenyByDefaultGate.GATE_NAME,   "dry-run: write gates suppressed"));
            } else {
                artifact.add(AuditRevisionGate.run(client, config, createdIds));
                artifact.add(InventoryIntegrityGate.run(client, config, createdIds));
                artifact.add(GuardNeverFailsOpenGate.run(client, config, createdIds));

                String pt = probeToken;
                if (pt != null) {
                    artifact.add(DenyByDefaultGate.run(client, config, createdIds, pt));
                } else {
                    artifact.add(GateResult.fail(DenyByDefaultGate.GATE_NAME, 0,
                            "Probe account unavailable — cannot test deny-by-default",
                            FailureClass.ENVIRONMENT_SETUP));
                }
            }

            // ── Cleanup ───────────────────────────────────────────────────
            boolean cleanOk = performCleanup(client, createdIds);
            artifact.completeCleanup(cleanOk);

        } catch (Exception e) {
            artifact.add(GateResult.error("RUNNER", 0, e));
            artifact.completeCleanup(false);
        }

        return artifact;
    }

    /**
     * Cancels all created work orders. Returns true only when all succeed.
     * Cleanup failures are reported separately from gate failures.
     */
    private static boolean performCleanup(HttpGateClient client, List<String> createdIds) {
        boolean allClean = true;
        for (String woId : createdIds) {
            try {
                cancelWorkOrder(client, woId);
            } catch (Exception e) {
                System.err.println("CLEANUP WARNING: failed to cancel WO " + woId + ": " + e.getMessage());
                allClean = false;
            }
        }
        return allClean;
    }

    private static void cancelWorkOrder(HttpGateClient client, String woId) throws Exception {
        HttpResponse<String> getResp = client.get("/api/v1/work-orders/" + woId);
        if (getResp.statusCode() != 200) return; // already gone

        Map<String, Object> wo = client.parseJson(getResp.body());
        Object stateObj = wo.get("state");
        if (stateObj instanceof String s && (s.equals("CANCELLED") || s.equals("CLOSED"))) {
            return; // already terminal
        }
        int version = SmokePath.extractInt(wo, "version");
        String body = """
                {"event":"CANCEL","expectedVersion":%d,"reason":"gate-suite cleanup run=%s"}
                """.formatted(version, woId);
        client.post("/api/v1/work-orders/" + woId + "/transitions", body);
    }
}
