package com.fieldservice.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Machine-readable result artifact written at the end of a suite run.
 *
 * <p>Schema:
 * <pre>
 * {
 *   "runId":      "string",
 *   "timestamp":  "ISO-8601",
 *   "baseUrl":    "string",
 *   "dryRun":     true|false,
 *   "overallPass": true|false,
 *   "gates": [
 *     {
 *       "name":          "string",
 *       "status":        "PASS|FAIL|SKIP|SETUP_ERROR|TRANSPORT_ERROR",
 *       "durationMs":    123,
 *       "failureDetail": "string|null"
 *     }
 *   ],
 *   "cleanupOutcome": "PASS|FAIL|SKIPPED",
 *   "cleanupDetail":  "string|null"
 * }
 * </pre>
 *
 * <p>No credentials, tokens, or personal data are included.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultsArtifact(
        String runId,
        String timestamp,
        String baseUrl,
        boolean dryRun,
        boolean overallPass,
        List<GateResult> gates,
        String cleanupOutcome,
        String cleanupDetail
) {

    /** Build an artifact from completed gate results. */
    public static ResultsArtifact of(
            RunConfig config,
            List<GateResult> gates,
            CleanupOutcome cleanup
    ) {
        boolean pass = gates.stream().noneMatch(GateResult::isGateFailure);

        return new ResultsArtifact(
                config.runId(),
                Instant.now().toString(),
                config.baseUrl(),
                config.dryRun(),
                pass,
                List.copyOf(gates),
                cleanup.name(),
                cleanup.detail()
        );
    }

    /** Write the artifact to the configured output path. */
    public void writeTo(String outputPath, ObjectMapper mapper) throws IOException {
        ObjectMapper pretty = mapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
        String json = pretty.writeValueAsString(this);
        Files.writeString(Path.of(outputPath), json);
    }

    /**
     * Print a human-readable summary to the given writer.
     *
     * <p>Format:
     * <pre>
     * ═══ Release Validation Suite ════════════════════
     * Run ID   : val-abc123
     * Target   : https://api.example.com
     * Dry run  : false
     * ─────────────────────────────────────────────────
     *   PASS  smoke-path                     (123ms)
     *   PASS  audit-revision-gate            (456ms)
     *   FAIL  inventory-integrity-gate       (789ms)  ← Stock went negative
     *   SKIP  guard-never-fails-open-gate    —        (dry-run)
     * ─────────────────────────────────────────────────
     * Cleanup  : PASS
     * OVERALL  : FAIL  (1 gate failure)
     * ═════════════════════════════════════════════════
     * </pre>
     */
    public void printSummary(PrintWriter out) {
        out.println("═══ Release Validation Suite ════════════════════");
        out.printf("Run ID   : %s%n", runId);
        out.printf("Target   : %s%n", baseUrl);
        out.printf("Dry run  : %b%n", dryRun);
        out.println("─────────────────────────────────────────────────");

        for (GateResult g : gates) {
            String line = String.format("  %-6s %-38s", g.status().name(), g.name());
            if (g.durationMs() > 0) {
                line += String.format("(%dms)", g.durationMs());
            } else {
                line += "—";
            }
            if (g.failureDetail() != null) {
                line += "  ← " + g.failureDetail();
            }
            out.println(line);
        }

        out.println("─────────────────────────────────────────────────");
        out.printf("Cleanup  : %s%s%n", cleanupOutcome,
                cleanupDetail != null ? "  ← " + cleanupDetail : "");

        long failures = gates.stream().filter(GateResult::isGateFailure).count();
        if (overallPass) {
            out.println("OVERALL  : PASS");
        } else {
            out.printf("OVERALL  : FAIL  (%d gate failure%s)%n",
                    failures, failures == 1 ? "" : "s");
        }
        out.println("═════════════════════════════════════════════════");
        out.flush();
    }

    /** Determines the process exit code from this artifact. */
    public int exitCode() {
        if (overallPass) return 0;
        boolean hasSetupProblem = gates.stream().anyMatch(GateResult::isSetupProblem);
        boolean hasTransport = gates.stream()
                .anyMatch(r -> r.status() == GateStatus.TRANSPORT_ERROR);
        if (hasTransport) return 3;
        if (hasSetupProblem) return 2;
        return 1; // genuine invariant violation
    }

    /** Outcome of the post-run cleanup phase. */
    public enum CleanupOutcome {
        PASS(null),
        FAIL(null),
        SKIPPED(null);

        private String detail;

        CleanupOutcome(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }

        public static CleanupOutcome pass() {
            return PASS;
        }

        public static CleanupOutcome fail(String detail) {
            CleanupOutcome o = FAIL;
            o.detail = detail;
            return o;
        }

        public static CleanupOutcome skipped() {
            return SKIPPED;
        }
    }
}
