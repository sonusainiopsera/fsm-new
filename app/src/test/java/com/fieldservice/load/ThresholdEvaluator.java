package com.fieldservice.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a committed {@code thresholds.json} and a machine-readable load-test results file,
 * decides pass or fail per metric, and accumulates violations.
 *
 * <p>Decision rules:
 * <ul>
 *   <li>A metric present in thresholds.json but absent from the results file is a FAIL
 *       with reason {@code METRIC_MISSING}. Missing metrics never pass by absence.</li>
 *   <li>A p95 exactly equal to the threshold (comparator {@code <=}) PASSES — the
 *       boundary is inclusive so the Phase 2 exit criterion is unambiguous.</li>
 *   <li>An unknown comparator string is treated as a configuration error and throws
 *       {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ThresholdEvaluator evaluator = ThresholdEvaluator.fromClasspath("/load/config/thresholds.json");
 * ThresholdEvaluator.Result result = evaluator.evaluate(Path.of("target/load-results.json"));
 * if (!result.passed()) {
 *     result.violations().forEach(System.err::println);
 *     System.exit(1);
 * }
 * }</pre>
 */
public final class ThresholdEvaluator {

    private final List<ThresholdEntry> thresholds;

    private ThresholdEvaluator(List<ThresholdEntry> thresholds) {
        this.thresholds = List.copyOf(thresholds);
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /** Loads thresholds from a classpath resource (e.g. {@code /load/config/thresholds.json}). */
    public static ThresholdEvaluator fromClasspath(String resourcePath) {
        try (InputStream is = ThresholdEvaluator.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException(
                        "Thresholds resource not found on classpath: " + resourcePath);
            }
            return parse(new ObjectMapper().readTree(is));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load thresholds from classpath: " + resourcePath, e);
        }
    }

    /** Loads thresholds from a file-system path. */
    public static ThresholdEvaluator fromFile(Path thresholdsPath) {
        try {
            return parse(new ObjectMapper().readTree(thresholdsPath.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load thresholds: " + thresholdsPath, e);
        }
    }

    private static ThresholdEvaluator parse(JsonNode root) {
        JsonNode array = root.get("thresholds");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("thresholds.json must contain a 'thresholds' array");
        }
        List<ThresholdEntry> entries = new ArrayList<>();
        for (JsonNode node : array) {
            String metric = requireText(node, "metric");
            String comparator = requireText(node, "comparator");
            double limit = node.get("limit").asDouble();
            validateComparator(comparator);
            entries.add(new ThresholdEntry(metric, comparator, limit));
        }
        return new ThresholdEvaluator(entries);
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /**
     * Evaluates the results file against all declared thresholds.
     *
     * @param resultsPath path to the machine-readable results JSON file
     * @return a {@link Result} — always non-null; check {@link Result#passed()}
     */
    public Result evaluate(Path resultsPath) {
        if (!Files.exists(resultsPath)) {
            throw new IllegalArgumentException("Results file does not exist: " + resultsPath);
        }
        JsonNode results;
        try {
            results = new ObjectMapper().readTree(resultsPath.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read results file: " + resultsPath, e);
        }
        return evaluate(results);
    }

    /** Evaluates against an already-parsed results JSON node. Used by unit tests. */
    public Result evaluate(JsonNode results) {
        List<Violation> violations = new ArrayList<>();
        for (ThresholdEntry entry : thresholds) {
            JsonNode valueNode = results.get(entry.metric());
            if (valueNode == null || valueNode.isNull()) {
                violations.add(new Violation(
                        entry.metric(), entry.comparator(), entry.limit(),
                        null, "METRIC_MISSING"));
                continue;
            }
            double actual = valueNode.asDouble();
            if (!satisfies(actual, entry.comparator(), entry.limit())) {
                violations.add(new Violation(
                        entry.metric(), entry.comparator(), entry.limit(),
                        actual, "THRESHOLD_BREACHED"));
            }
        }
        return new Result(violations);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static boolean satisfies(double actual, String comparator, double limit) {
        return switch (comparator) {
            case "<="  -> actual <= limit;
            case "<"   -> actual < limit;
            case ">="  -> actual >= limit;
            case ">"   -> actual > limit;
            default    -> throw new IllegalArgumentException("Unknown comparator: " + comparator);
        };
    }

    private static void validateComparator(String comparator) {
        if (!comparator.equals("<=") && !comparator.equals("<")
                && !comparator.equals(">=") && !comparator.equals(">")) {
            throw new IllegalArgumentException(
                    "Unsupported comparator '" + comparator + "'; allowed: <=, <, >=, >");
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isTextual()) {
            throw new IllegalArgumentException(
                    "thresholds.json entry missing required text field '" + field + "'");
        }
        return child.asText();
    }

    // -----------------------------------------------------------------------
    // Value types
    // -----------------------------------------------------------------------

    private record ThresholdEntry(String metric, String comparator, double limit) {}

    /** A single threshold violation. */
    public record Violation(
            String metric,
            String comparator,
            double limit,
            Double actual,   // null when METRIC_MISSING
            String reason) {

        @Override
        public String toString() {
            if (actual == null) {
                return String.format("FAIL [%s] metric absent from results file", metric);
            }
            return String.format("FAIL [%s] actual=%.3f %s limit=%.3f (reason=%s)",
                    metric, actual, comparator, limit, reason);
        }
    }

    /** Aggregated evaluation outcome. */
    public record Result(List<Violation> violations) {

        public Result {
            violations = List.copyOf(violations);
        }

        public boolean passed() {
            return violations.isEmpty();
        }

        public void printReport(java.io.PrintStream out) {
            if (passed()) {
                out.println("PASS — all thresholds satisfied");
            } else {
                out.println("FAIL — " + violations.size() + " threshold(s) breached:");
                violations.forEach(v -> out.println("  " + v));
            }
        }
    }
}
