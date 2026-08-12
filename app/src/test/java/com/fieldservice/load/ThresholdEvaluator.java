package com.fieldservice.load;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a load-test results JSON file and evaluates it against the committed
 * threshold configuration, producing a pass/fail decision with per-metric verdicts.
 *
 * <p>Contract:
 * <ul>
 *   <li>Every metric listed in the threshold configuration MUST appear in the results
 *       file. A missing metric is an {@link EvaluationResult.Verdict#MISSING_METRIC}
 *       failure — it never passes by absence.</li>
 *   <li>Comparators: {@code lte} (≤), {@code lt} (<), {@code gte} (≥), {@code gt} (>),
 *       {@code eq} (==). A value exactly equal to the limit passes for {@code lte}/
 *       {@code gte} and fails for {@code lt}/{@code gt}.</li>
 *   <li>The evaluator never throws on a bad comparator string — it emits
 *       {@link EvaluationResult.Verdict#UNKNOWN_COMPARATOR} for that metric.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   ThresholdEvaluator evaluator = new ThresholdEvaluator();
 *   EvaluationResult result = evaluator.evaluate(
 *       Path.of("src/test/load/config/thresholds.json"),
 *       Path.of("target/load-results/results.json"),
 *       "healthy");
 *   if (!result.passed()) { System.exit(1); }
 * }</pre>
 *
 * <h3>Results file format</h3>
 * The results file must be a JSON object mapping metric name (string) to numeric
 * value (number):
 * <pre>{@code
 * {
 *   "recommendations.latency.p95.ms": 1842,
 *   "all.error.rate.percent": 0.3,
 *   "travel.cache.hit.ratio": 0.76
 * }
 * }</pre>
 *
 * <h3>Thresholds file format</h3>
 * See {@code src/test/load/config/thresholds.json} — a JSON object with a
 * {@code thresholds} array (healthy) and a {@code degradedThresholds} array (degraded).
 */
public class ThresholdEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Evaluates the results file against the thresholds for the given profile key.
     *
     * @param thresholdsFile path to the thresholds.json configuration
     * @param resultsFile    path to the machine-readable results JSON
     * @param profileKey     {@code "healthy"} uses {@code thresholds[]};
     *                       {@code "degraded"} uses {@code degradedThresholds[]}
     * @return the evaluation result, never null; call {@link EvaluationResult#passed()} to gate
     * @throws IOException if either file cannot be read or parsed
     */
    public EvaluationResult evaluate(Path thresholdsFile,
                                     Path resultsFile,
                                     String profileKey) throws IOException {

        Map<String, Object> thresholdsDoc = MAPPER.readValue(
                Files.readString(thresholdsFile),
                new TypeReference<>() {});

        Map<String, Number> results = MAPPER.readValue(
                Files.readString(resultsFile),
                new TypeReference<>() {});

        String arrayKey = "degraded".equalsIgnoreCase(profileKey)
                ? "degradedThresholds"
                : "thresholds";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thresholds = (List<Map<String, Object>>) thresholdsDoc.get(arrayKey);
        if (thresholds == null) {
            throw new IOException("Thresholds file missing key '" + arrayKey + "'");
        }

        List<EvaluationResult.MetricVerdict> verdicts = new ArrayList<>();

        for (Map<String, Object> threshold : thresholds) {
            String metric     = (String) threshold.get("metric");
            String comparator = (String) threshold.get("comparator");
            double limit      = ((Number) threshold.get("limit")).doubleValue();
            String desc       = (String) threshold.getOrDefault("description", metric);

            if (!results.containsKey(metric)) {
                verdicts.add(new EvaluationResult.MetricVerdict(
                        metric, EvaluationResult.Verdict.MISSING_METRIC,
                        null, limit, comparator, desc));
                continue;
            }

            double actual = results.get(metric).doubleValue();
            EvaluationResult.Verdict verdict = applyComparator(actual, comparator, limit);
            verdicts.add(new EvaluationResult.MetricVerdict(metric, verdict, actual, limit, comparator, desc));
        }

        return new EvaluationResult(verdicts);
    }

    private static EvaluationResult.Verdict applyComparator(double actual,
                                                              String comparator,
                                                              double limit) {
        boolean pass = switch (comparator) {
            case "lte" -> actual <= limit;
            case "lt"  -> actual <  limit;
            case "gte" -> actual >= limit;
            case "gt"  -> actual >  limit;
            case "eq"  -> Double.compare(actual, limit) == 0;
            default    -> { yield false; }
        };

        if ("lte".equals(comparator) || "lt".equals(comparator) || "gte".equals(comparator)
                || "gt".equals(comparator) || "eq".equals(comparator)) {
            return pass ? EvaluationResult.Verdict.PASS : EvaluationResult.Verdict.FAIL;
        }
        return EvaluationResult.Verdict.UNKNOWN_COMPARATOR;
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Immutable result of a threshold evaluation run.
     */
    public record EvaluationResult(List<MetricVerdict> verdicts) {

        /** @return true only when every metric verdict is {@link Verdict#PASS} */
        public boolean passed() {
            return verdicts.stream().allMatch(v -> v.verdict() == Verdict.PASS);
        }

        /** @return human-readable summary suitable for CI log output */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Threshold evaluation: ").append(passed() ? "PASS" : "FAIL").append('\n');
            for (MetricVerdict v : verdicts) {
                sb.append("  [").append(v.verdict().name()).append("] ")
                  .append(v.metric()).append(" = ")
                  .append(v.actual() != null ? String.format("%.3f", v.actual()) : "<missing>")
                  .append(" (").append(v.comparator()).append(' ')
                  .append(String.format("%.3f", v.limit())).append(')')
                  .append('\n');
            }
            return sb.toString();
        }

        public record MetricVerdict(
                String  metric,
                Verdict verdict,
                Double  actual,
                double  limit,
                String  comparator,
                String  description
        ) {}

        public enum Verdict {
            PASS,
            FAIL,
            MISSING_METRIC,
            UNKNOWN_COMPARATOR
        }
    }
}
