package com.fieldservice.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Machine-readable JSON artifact written at the end of a validation run.
 *
 * <p>Schema:
 * <pre>{@code
 * {
 *   "runId":      "string",
 *   "environment":"string",
 *   "startedAt":  "ISO-8601",
 *   "finishedAt": "ISO-8601",
 *   "durationMs": 12345,
 *   "overallPass":true,
 *   "dryRun":     false,
 *   "gates": [
 *     {
 *       "gate":     "AuditRevisionGate",
 *       "status":   "PASS",
 *       "durationMs": 234,
 *       "detail":   "..."
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>No credentials, tokens, or personal data appear in the artifact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultsArtifact(
        String runId,
        String environment,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        boolean overallPass,
        boolean dryRun,
        List<GateEntry> gates
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GateEntry(
            String gate,
            String status,
            long durationMs,
            String detail
    ) {
        static GateEntry from(GateResult r) {
            return new GateEntry(
                    r.gateName(),
                    r.status().name(),
                    r.duration().toMillis(),
                    r.detail()
            );
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Builds a ResultsArtifact from the collected gate results. */
    public static ResultsArtifact of(
            String runId,
            String environment,
            Instant startedAt,
            Instant finishedAt,
            boolean dryRun,
            List<GateResult> results) {

        boolean allPass = results.stream().noneMatch(GateResult::failed);
        List<GateEntry> entries = results.stream().map(GateEntry::from).toList();
        long ms = Duration.between(startedAt, finishedAt).toMillis();
        return new ResultsArtifact(runId, environment, startedAt, finishedAt,
                ms, allPass, dryRun, entries);
    }

    /** Serialises this artifact to JSON and writes it to {@code path}. */
    public void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
    }

    /** Returns the JSON representation as a string. */
    public String toJson() throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    /** Prints a human-readable summary to {@code out}. */
    public void printSummary(PrintWriter out) {
        out.printf("=== Post-Deploy Validation (%s) ===%n", runId);
        out.printf("Environment : %s%n", environment);
        out.printf("Started     : %s%n", startedAt);
        out.printf("Duration    : %d ms%n", durationMs);
        out.printf("Dry-run     : %s%n", dryRun);
        out.printf("Overall     : %s%n", overallPass ? "PASS ✓" : "FAIL ✗");
        out.println();
        for (GateEntry g : gates) {
            String icon = switch (g.status()) {
                case "PASS"        -> "✓";
                case "FAIL"        -> "✗";
                case "SKIP"        -> "–";
                case "SETUP_ERROR" -> "!";
                default            -> "?";
            };
            out.printf("  [%s] %-40s %s  (%d ms)%n", icon, g.gate(), g.status(), g.durationMs());
            if (!"PASS".equals(g.status()) && g.detail() != null && !g.detail().isBlank()) {
                out.printf("      %s%n", g.detail());
            }
        }
        out.flush();
    }

    /** Returns the suggested process exit code: 0 = all pass, 1 = any failure. */
    public int exitCode() {
        return overallPass ? 0 : 1;
    }
}
