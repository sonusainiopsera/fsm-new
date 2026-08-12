package com.fieldservice.release;

import com.fieldservice.release.ResultsArtifact.CleanupOutcome;
import com.fieldservice.release.gates.AuditRevisionGate;
import com.fieldservice.release.gates.DenyByDefaultGate;
import com.fieldservice.release.gates.GuardNeverFailsOpenGate;
import com.fieldservice.release.gates.InventoryIntegrityGate;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the post-deploy invariant gate suite.
 *
 * <p>Usage:
 * <pre>
 *   java -cp field-service-api-tests.jar com.fieldservice.release.InvariantGateRunner \
 *     [--dry-run] [--output path/to/results.json]
 *
 *   # Configuration via environment variables:
 *   VALIDATION_BASE_URL=https://api.example.com
 *   VALIDATION_USERNAME=validator@example.test
 *   VALIDATION_PASSWORD=<from secrets store>
 *   VALIDATION_ADMIN_USERNAME=admin@example.test
 *   VALIDATION_ADMIN_PASSWORD=<from secrets store>
 *   VALIDATION_RUN_ID=deploy-20260101-1           # optional, auto-generated if absent
 *   VALIDATION_DRY_RUN=false                       # optional
 *   VALIDATION_OUTPUT=results.json                 # optional
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — all gates passed</li>
 *   <li>1 — one or more invariant violations or smoke-path failures (block promotion)</li>
 *   <li>2 — environment setup error (missing account, part, or configuration)</li>
 *   <li>3 — transport error (network unreachable after retries)</li>
 * </ul>
 *
 * <p>No credential or token is written to the artifact or logs.
 */
public class InvariantGateRunner {

    private static final Logger LOG = Logger.getLogger(InvariantGateRunner.class.getName());

    /** Ordered gate sequence. Smoke path runs first as a fast-fail on connectivity. */
    static List<Gate> defaultGates() {
        return List.of(
                new SmokePath(),
                new AuditRevisionGate(),
                new InventoryIntegrityGate(),
                new GuardNeverFailsOpenGate(),
                new DenyByDefaultGate()
        );
    }

    // ── main() ───────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        boolean dryRun = containsFlag(args, "--dry-run");
        String output = flagValue(args, "--output");

        RunConfig config;
        try {
            config = RunConfig.fromEnvironment();
            if (dryRun) {
                // Override dryRun from CLI flag even if env var says false
                config = new RunConfig(
                        config.baseUrl(), config.username(), config.password(),
                        config.adminUsername(), config.adminPassword(),
                        config.runId(), true,
                        output != null ? output : config.outputPath());
            } else if (output != null) {
                config = new RunConfig(
                        config.baseUrl(), config.username(), config.password(),
                        config.adminUsername(), config.adminPassword(),
                        config.runId(), config.dryRun(), output);
            }
        } catch (IllegalStateException e) {
            System.err.println("SETUP ERROR: " + e.getMessage());
            System.exit(2);
            return;
        }

        int exitCode = run(config, defaultGates(), new PrintWriter(System.out, true));
        System.exit(exitCode);
    }

    // ── run() — testable entry point ──────────────────────────────────────────

    /**
     * Execute the suite and return the exit code.
     *
     * @param config  run configuration
     * @param gates   ordered gate list (injected for testing)
     * @param out     summary output writer
     * @return exit code: 0=pass, 1=gate failure, 2=setup error, 3=transport error
     */
    public static int run(RunConfig config, List<Gate> gates, PrintWriter out) {
        ApiClient client = ApiClient.create();

        // Authenticate before running gates
        if (!config.dryRun()) {
            try {
                client.login(config.baseUrl(), config.username(), config.password());
            } catch (ApiClient.TransportException e) {
                out.println("TRANSPORT ERROR: Cannot reach " + config.baseUrl() +
                        " — " + e.getMessage());
                return 3;
            } catch (ApiClient.SetupException e) {
                out.println("SETUP ERROR: " + e.getMessage());
                return 2;
            }
        }

        List<GateResult> results = new ArrayList<>();
        CleanupOutcome cleanup = CleanupOutcome.skipped();

        for (Gate gate : gates) {
            LOG.info("Running gate: " + gate.name());
            try {
                GateResult result = gate.run(config, client);
                results.add(result);
                logResult(result);
            } catch (Exception e) {
                // Gates must not throw — this is a programming error
                LOG.log(Level.SEVERE, "Gate threw unexpectedly: " + gate.name(), e);
                results.add(GateResult.fail(gate.name(), 0,
                        "Gate threw unexpected exception: " + e.getClass().getSimpleName()));
            }
        }

        // ── Cleanup pass ──────────────────────────────────────────────────────
        cleanup = runCleanup(config, client, results);

        // ── Write artifact ────────────────────────────────────────────────────
        ResultsArtifact artifact = ResultsArtifact.of(config, results, cleanup);

        try {
            artifact.writeTo(config.outputPath(), new com.fasterxml.jackson.databind.ObjectMapper());
        } catch (Exception e) {
            LOG.warning("Could not write results artifact to " + config.outputPath() +
                    ": " + e.getMessage());
        }

        // ── Print summary ─────────────────────────────────────────────────────
        artifact.printSummary(out);

        return artifact.exitCode();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Close or cancel all records tagged with the run identifier.
     *
     * <p>The suite tags every created record with the run identifier in a documented
     * free-text field (e.g. {@code validationRunId}). This method queries records
     * tagged with the run identifier and cancels or closes them.
     *
     * <p>Cleanup failure is reported separately from gate failures — a cleanup failure
     * does not change the gate outcome.
     */
    static CleanupOutcome runCleanup(RunConfig config, ApiClient client,
                                     List<GateResult> completedResults) {
        if (config.dryRun()) {
            return CleanupOutcome.skipped();
        }

        try {
            // Re-authenticate as admin for cleanup
            client.login(config.baseUrl(), config.adminUsername(), config.adminPassword());

            // Query work orders tagged with this run identifier
            String searchUrl = config.url(
                    "/api/v1/work-orders?validationRunId=" + config.runId() + "&size=50");
            ApiClient.ApiResponse searchResp = client.get(searchUrl);

            if (!searchResp.is2xx() && searchResp.status() != 404) {
                return CleanupOutcome.fail(
                        "Cleanup query returned " + searchResp.status());
            }

            if (searchResp.status() == 404) {
                // Work-order API not yet deployed — nothing to clean up
                return CleanupOutcome.pass();
            }

            com.fasterxml.jackson.databind.JsonNode json =
                    searchResp.json(new com.fasterxml.jackson.databind.ObjectMapper());
            com.fasterxml.jackson.databind.JsonNode data = json.path("data");

            if (!data.isArray()) {
                return CleanupOutcome.pass();
            }

            List<String> failures = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : data) {
                String id = item.path("id").asText(null);
                String state = item.path("state").asText("");
                if (id == null) continue;
                if ("CANCELLED".equals(state) || "CLOSED".equals(state)) continue;

                try {
                    ApiClient.ApiResponse cancelResp = client.put(
                            config.url("/api/v1/work-orders/" + id + "/state"),
                            "{\"state\":\"CANCELLED\"}");
                    if (!cancelResp.is2xx()) {
                        failures.add(id + " (status=" + cancelResp.status() + ")");
                    }
                } catch (ApiClient.TransportException e) {
                    failures.add(id + " (transport error)");
                }
            }

            if (!failures.isEmpty()) {
                return CleanupOutcome.fail(
                        "Could not cancel " + failures.size() + " record(s): " + failures);
            }

            return CleanupOutcome.pass();

        } catch (ApiClient.TransportException e) {
            return CleanupOutcome.fail("Transport error during cleanup: " + e.getMessage());
        } catch (ApiClient.SetupException e) {
            return CleanupOutcome.fail("Admin login failed during cleanup: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void logResult(GateResult result) {
        String msg = String.format("[%s] %s (%dms)%s",
                result.status(), result.name(), result.durationMs(),
                result.failureDetail() != null ? " — " + result.failureDetail() : "");
        if (result.isGateFailure()) {
            LOG.warning(msg);
        } else {
            LOG.info(msg);
        }
    }

    static boolean containsFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }

    static String flagValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return null;
    }
}
