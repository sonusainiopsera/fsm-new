package com.fieldservice.load;

import com.fieldservice.load.ThresholdEvaluator.EvaluationResult;
import com.fieldservice.load.ThresholdEvaluator.EvaluationResult.MetricVerdict;
import com.fieldservice.load.ThresholdEvaluator.EvaluationResult.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ThresholdEvaluator}.
 *
 * <p>No Spring context. Uses temporary files to exercise the full file-read and evaluation path.
 */
class ThresholdEvaluatorTest {

    @TempDir
    Path tempDir;

    ThresholdEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ThresholdEvaluator();
    }

    // ── Pass cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PASS: all metrics within thresholds")
    void evaluate_allPass() throws IOException {
        Path thresholds = writeThresholds("""
            {
              "thresholds": [
                {"metric": "recommendations.latency.p95.ms", "comparator": "lte", "limit": 3000, "description": "p95"},
                {"metric": "all.error.rate.percent",         "comparator": "lt",  "limit": 1.0,  "description": "err"}
              ]
            }
            """);

        Path results = writeResults("""
            {
              "recommendations.latency.p95.ms": 1800,
              "all.error.rate.percent": 0.4
            }
            """);

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isTrue();
        assertThat(result.verdicts()).allMatch(v -> v.verdict() == Verdict.PASS);
    }

    @Test
    @DisplayName("PASS: value exactly equal to lte threshold (boundary = pass)")
    void evaluate_boundaryEqual_lte_passes() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "latency.p95", "comparator": "lte", "limit": 3000}]}
            """);
        Path results = writeResults("""{"latency.p95": 3000}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isTrue();
        assertVerdict(result, "latency.p95", Verdict.PASS);
    }

    @Test
    @DisplayName("PASS: value exactly equal to gte threshold (boundary = pass)")
    void evaluate_boundaryEqual_gte_passes() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "cache.hit.ratio", "comparator": "gte", "limit": 0.5}]}
            """);
        Path results = writeResults("""{"cache.hit.ratio": 0.5}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isTrue();
    }

    // ── Fail cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FAIL: p95 exceeds lte threshold")
    void evaluate_p95_exceeds_threshold_fails() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "recommendations.latency.p95.ms", "comparator": "lte", "limit": 3000}]}
            """);
        Path results = writeResults("""{"recommendations.latency.p95.ms": 3001}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isFalse();
        assertVerdict(result, "recommendations.latency.p95.ms", Verdict.FAIL);
    }

    @Test
    @DisplayName("FAIL: error rate equals lt threshold (boundary = fail for strict lt)")
    void evaluate_boundaryEqual_lt_fails() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "all.error.rate.percent", "comparator": "lt", "limit": 1.0}]}
            """);
        Path results = writeResults("""{"all.error.rate.percent": 1.0}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isFalse();
        assertVerdict(result, "all.error.rate.percent", Verdict.FAIL);
    }

    @Test
    @DisplayName("FAIL: one metric fails while others pass — overall result is fail")
    void evaluate_oneMetricFails_overallFails() throws IOException {
        Path thresholds = writeThresholds("""
            {
              "thresholds": [
                {"metric": "latency.p95", "comparator": "lte", "limit": 3000},
                {"metric": "error.rate",  "comparator": "lt",  "limit": 1.0}
              ]
            }
            """);
        Path results = writeResults("""
            {
              "latency.p95": 1000,
              "error.rate": 2.5
            }
            """);

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isFalse();
        assertVerdict(result, "latency.p95", Verdict.PASS);
        assertVerdict(result, "error.rate",  Verdict.FAIL);
    }

    // ── Missing metric ────────────────────────────────────────────────────────

    @Test
    @DisplayName("MISSING_METRIC: metric in thresholds but absent from results fails evaluation")
    void evaluate_missingMetric_fails() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "recommendations.latency.p95.ms", "comparator": "lte", "limit": 3000}]}
            """);
        Path results = writeResults("""{}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        assertThat(result.passed()).isFalse();
        assertVerdict(result, "recommendations.latency.p95.ms", Verdict.MISSING_METRIC);
    }

    @Test
    @DisplayName("MISSING_METRIC: actual value is null in the verdict")
    void evaluate_missingMetric_hasNullActual() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "gone.metric", "comparator": "lte", "limit": 100}]}
            """);
        Path results = writeResults("""{}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");

        MetricVerdict verdict = result.verdicts().get(0);
        assertThat(verdict.actual()).isNull();
        assertThat(verdict.verdict()).isEqualTo(Verdict.MISSING_METRIC);
    }

    // ── Degraded profile ──────────────────────────────────────────────────────

    @Test
    @DisplayName("degraded profile reads degradedThresholds array")
    void evaluate_degradedProfile_usesDegradedThresholds() throws IOException {
        Path thresholds = writeThresholds("""
            {
              "thresholds": [
                {"metric": "healthy.only", "comparator": "lte", "limit": 100}
              ],
              "degradedThresholds": [
                {"metric": "degraded.only", "comparator": "lte", "limit": 3000}
              ]
            }
            """);
        Path results = writeResults("""{"degraded.only": 2500}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "degraded");

        assertThat(result.passed()).isTrue();
        assertThat(result.verdicts()).hasSize(1);
        assertVerdict(result, "degraded.only", Verdict.PASS);
    }

    // ── IO errors ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IOException when thresholds file does not exist")
    void evaluate_missingThresholdsFile_throws() {
        Path missing = tempDir.resolve("missing.json");
        Path results = tempDir.resolve("results.json");

        assertThatThrownBy(() -> evaluator.evaluate(missing, results, "healthy"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("IOException when results file does not exist")
    void evaluate_missingResultsFile_throws() throws IOException {
        Path thresholds = writeThresholds("""{"thresholds": []}""");
        Path missing = tempDir.resolve("no-results.json");

        assertThatThrownBy(() -> evaluator.evaluate(thresholds, missing, "healthy"))
                .isInstanceOf(IOException.class);
    }

    // ── Summary format ────────────────────────────────────────────────────────

    @Test
    @DisplayName("summary contains PASS marker when all metrics pass")
    void evaluate_summary_containsPassMarker() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "latency.p95", "comparator": "lte", "limit": 3000}]}
            """);
        Path results = writeResults("""{"latency.p95": 1000}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");
        assertThat(result.summary()).contains("PASS");
    }

    @Test
    @DisplayName("summary contains FAIL marker when any metric fails")
    void evaluate_summary_containsFailMarker() throws IOException {
        Path thresholds = writeThresholds("""
            {"thresholds": [{"metric": "latency.p95", "comparator": "lte", "limit": 3000}]}
            """);
        Path results = writeResults("""{"latency.p95": 9999}""");

        EvaluationResult result = evaluator.evaluate(thresholds, results, "healthy");
        assertThat(result.summary()).contains("FAIL");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path writeThresholds(String json) throws IOException {
        Path file = tempDir.resolve("thresholds.json");
        Files.writeString(file, json);
        return file;
    }

    private Path writeResults(String json) throws IOException {
        Path file = tempDir.resolve("results.json");
        Files.writeString(file, json);
        return file;
    }

    private void assertVerdict(EvaluationResult result, String metric, Verdict expected) {
        Optional<MetricVerdict> found = result.verdicts().stream()
                .filter(v -> v.metric().equals(metric))
                .findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().verdict()).isEqualTo(expected);
    }
}
