package com.fieldservice.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ThresholdEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── ThresholdEvaluator construction ─────────────────────────────────────

    @Test
    void loadsFromFile(@TempDir Path tmp) throws IOException {
        Path file = writeThresholds(tmp,
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        ThresholdEvaluator evaluator = ThresholdEvaluator.fromFile(file);
        assertThat(evaluator).isNotNull();
    }

    @Test
    void rejectsUnknownComparator(@TempDir Path tmp) throws IOException {
        Path file = writeThresholds(tmp,
                singleThreshold("someMetric", "!=", 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ThresholdEvaluator.fromFile(file))
                .withMessageContaining("Unsupported comparator");
    }

    @Test
    void rejectsMissingThresholdsArray(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{\"description\": \"no thresholds key\"}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ThresholdEvaluator.fromFile(file))
                .withMessageContaining("'thresholds' array");
    }

    // ─── Pass cases ──────────────────────────────────────────────────────────

    @Test
    void passesWhenActualBelowLimit(@TempDir Path tmp) throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("recommendations.p95LatencyMs", 1500.0);

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void passesWhenActualStrictlyBelowForStrictLessThan(@TempDir Path tmp) throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("errorRate", "<", 0.01));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("errorRate", 0.005);

        assertThat(evaluator.evaluate(results).passed()).isTrue();
    }

    // ─── Boundary — equal-to-limit ────────────────────────────────────────────

    @Test
    void passesWhenActualExactlyEqualsLimitForLessOrEqual() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("recommendations.p95LatencyMs", 3000.0);

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed())
                .as("p95 exactly equal to threshold (<=) must PASS")
                .isTrue();
    }

    @Test
    void failsWhenActualExactlyEqualsLimitForStrictLessThan() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("errorRate", "<", 0.01));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("errorRate", 0.01);

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed())
                .as("errorRate exactly at strict limit (<) must FAIL")
                .isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).reason()).isEqualTo("THRESHOLD_BREACHED");
    }

    // ─── Fail cases ──────────────────────────────────────────────────────────

    @Test
    void failsWhenActualExceedsLimit() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("recommendations.p95LatencyMs", 3001.0);

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ThresholdEvaluator.Violation v = result.violations().get(0);
        assertThat(v.metric()).isEqualTo("recommendations.p95LatencyMs");
        assertThat(v.actual()).isEqualTo(3001.0);
        assertThat(v.reason()).isEqualTo("THRESHOLD_BREACHED");
    }

    @Test
    void accumulatesMultipleViolations() throws IOException {
        String thresholdsJson = """
                {
                  "thresholds": [
                    {"metric": "recommendations.p95LatencyMs", "comparator": "<=", "limit": 3000},
                    {"metric": "errorRate", "comparator": "<", "limit": 0.01},
                    {"metric": "recommendations.p50LatencyMs", "comparator": "<=", "limit": 1000}
                  ]
                }
                """;
        ThresholdEvaluator evaluator = ThresholdEvaluator.fromFile(writeJson(thresholdsJson));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("recommendations.p95LatencyMs", 4000.0); // breach
        results.put("errorRate", 0.02);                      // breach
        results.put("recommendations.p50LatencyMs", 800.0);  // pass

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(2);
    }

    // ─── Missing metric ───────────────────────────────────────────────────────

    @Test
    void failsWhenMetricMissingFromResults() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        ObjectNode results = MAPPER.createObjectNode();
        // deliberately not adding recommendations.p95LatencyMs

        ThresholdEvaluator.Result result = evaluator.evaluate(results);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ThresholdEvaluator.Violation v = result.violations().get(0);
        assertThat(v.reason()).isEqualTo("METRIC_MISSING");
        assertThat(v.actual()).isNull();
    }

    @Test
    void missingMetricNeverPassesByAbsence() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("travelProviderCallLatencyMs", "<=", 1500));
        ObjectNode emptyResults = MAPPER.createObjectNode();

        assertThat(evaluator.evaluate(emptyResults).passed())
                .as("absent metric must FAIL, not pass by absence")
                .isFalse();
    }

    // ─── Greater-than comparators ──────────────────────────────────────────────

    @Test
    void supportsGreaterThanComparators() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("cacheHitRate", ">=", 0.80));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("cacheHitRate", 0.95);

        assertThat(evaluator.evaluate(results).passed()).isTrue();
    }

    @Test
    void failsGreaterThanWhenBelowLimit() throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("cacheHitRate", ">=", 0.80));
        ObjectNode results = MAPPER.createObjectNode();
        results.put("cacheHitRate", 0.70);

        assertThat(evaluator.evaluate(results).passed()).isFalse();
    }

    // ─── Results-file-missing guard ───────────────────────────────────────────

    @Test
    void throwsWhenResultsFileDoesNotExist(@TempDir Path tmp) throws IOException {
        ThresholdEvaluator evaluator = evaluatorWith(
                singleThreshold("recommendations.p95LatencyMs", "<=", 3000));
        Path nonExistent = tmp.resolve("does-not-exist.json");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> evaluator.evaluate(nonExistent))
                .withMessageContaining("does not exist");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static ThresholdEvaluator evaluatorWith(String json) throws IOException {
        return ThresholdEvaluator.fromFile(writeJson(json));
    }

    // Uses a shared temp file at a fixed path acceptable by JUnit (not @TempDir)
    private static Path writeJson(String json) throws IOException {
        Path tmp = Files.createTempFile("thresholds-", ".json");
        tmp.toFile().deleteOnExit();
        Files.writeString(tmp, json);
        return tmp;
    }

    private static Path writeThresholds(Path dir, String json) throws IOException {
        Path file = dir.resolve("thresholds.json");
        Files.writeString(file, json);
        return file;
    }

    private static String singleThreshold(String metric, String comparator, double limit) {
        return String.format(
                "{\"thresholds\":[{\"metric\":\"%s\",\"comparator\":\"%s\",\"limit\":%s}]}",
                metric, comparator, limit);
    }
}
