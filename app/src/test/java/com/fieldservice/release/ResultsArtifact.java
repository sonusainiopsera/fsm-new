package com.fieldservice.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accumulates gate results, writes a machine-readable JSON artifact, prints a
 * human-readable summary, and provides the process exit code.
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 — all gates passed (skipped gates are not failures).</li>
 *   <li>1 — one or more gates failed or errored (invariant violation or smoke-path failure).</li>
 *   <li>2 — environment setup failure; the run could not start meaningfully.</li>
 * </ul>
 *
 * <h3>JSON schema</h3>
 * {@code { runId, startedAt, completedAt, overall, cleanupStatus, gates: [{name,status,durationMs,detail,failureClass}] }}
 */
public final class ResultsArtifact {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String      runId;
    private final Instant     startedAt;
    private final List<GateResult> gates = new ArrayList<>();
    private Instant     completedAt;
    private String      cleanupStatus = "PENDING";
    private boolean     setupFailed   = false;

    public ResultsArtifact(String runId) {
        this.runId     = runId;
        this.startedAt = Instant.now();
    }

    public void add(GateResult result) {
        gates.add(result);
    }

    public void completeCleanup(boolean success) {
        cleanupStatus = success ? "CLEAN" : "RESIDUE_PRESENT";
        completedAt   = Instant.now();
    }

    public void markSetupFailed(String detail) {
        setupFailed  = true;
        completedAt  = Instant.now();
        cleanupStatus = "NOT_STARTED";
        gates.add(GateResult.fail("SETUP", 0, detail, FailureClass.ENVIRONMENT_SETUP));
    }

    public boolean anyFailed() {
        return gates.stream().anyMatch(GateResult::failed);
    }

    public boolean setupDidFail() {
        return setupFailed;
    }

    /** Returns the process exit code: 0=pass, 1=gate failure, 2=setup failure. */
    public int exitCode() {
        if (setupFailed) return 2;
        return anyFailed() ? 1 : 0;
    }

    /** Writes the JSON artifact to {@code outputPath} and returns the JSON string. */
    public String write(Path outputPath) throws IOException {
        String json = toJson();
        Files.writeString(outputPath, json);
        return json;
    }

    public String toJson() throws JsonProcessingException {
        Instant ended = completedAt != null ? completedAt : Instant.now();
        long totalMs  = ended.toEpochMilli() - startedAt.toEpochMilli();

        List<Map<String, Object>> gateList = gates.stream().map(g -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name",         g.name());
            m.put("status",       g.status().name());
            m.put("durationMs",   g.durationMs());
            m.put("detail",       g.detail());
            if (g.failureClass() != null) m.put("failureClass", g.failureClass().name());
            return m;
        }).toList();

        Map<String, Object> artifact = new java.util.LinkedHashMap<>();
        artifact.put("runId",         runId);
        artifact.put("startedAt",     startedAt.toString());
        artifact.put("completedAt",   ended.toString());
        artifact.put("totalMs",       totalMs);
        artifact.put("overall",       exitCode() == 0 ? "PASS" : (setupFailed ? "SETUP_ERROR" : "FAIL"));
        artifact.put("cleanupStatus", cleanupStatus);
        artifact.put("gates",         gateList);

        return MAPPER.writeValueAsString(artifact);
    }

    /** Prints a human-readable summary to stdout. */
    public void printSummary() {
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  RELEASE VALIDATION  run=" + runId);
        System.out.println("═══════════════════════════════════════════════════");
        for (GateResult g : gates) {
            String icon = switch (g.status()) {
                case PASS  -> "✔";
                case FAIL, ERROR -> "✘";
                case SKIP  -> "⊘";
            };
            System.out.printf("  %s  %-35s  %5dms  %s%n",
                    icon, g.name(), g.durationMs(), g.detail());
        }
        System.out.println("───────────────────────────────────────────────────");
        System.out.println("  Overall: " + (exitCode() == 0 ? "PASS" : "FAIL")
                + "  cleanup=" + cleanupStatus);
        System.out.println("═══════════════════════════════════════════════════");
    }

    public List<GateResult> gates() {
        return List.copyOf(gates);
    }

    /** Compact one-line summary of all gate statuses, for test failure messages. */
    public String gatesSummary() {
        var sb = new StringBuilder();
        for (GateResult g : gates) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(g.name()).append('=').append(g.status());
            if (g.detail() != null && !g.detail().isBlank()) {
                sb.append('(').append(g.detail().length() > 80
                        ? g.detail().substring(0, 80) + "…" : g.detail()).append(')');
            }
        }
        if (setupFailed) sb.append(", SETUP_FAILED");
        return sb.toString();
    }
}
