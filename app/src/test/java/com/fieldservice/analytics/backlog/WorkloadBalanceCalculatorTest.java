package com.fieldservice.analytics.backlog;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.backlog.WorkloadBalanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for WorkloadBalanceCalculator.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Population CV formula against hand-computed datasets (not sample CV).</li>
 *   <li>NOT_MEANINGFUL returned for fewer than 3 active technicians.</li>
 *   <li>NOT_MEANINGFUL returned when mean assigned hours = 0.</li>
 *   <li>BASELINE_PENDING when baseline_metric table has no row.</li>
 *   <li>WORSENED / IMPROVED direction compared against a seeded baseline.</li>
 * </ul>
 *
 * <p><b>Population std-dev convention (lock test):</b>
 * For values [10, 6, 8]:
 *   mean       = 8.0
 *   sum sq-diff = (10-8)^2 + (6-8)^2 + (8-8)^2 = 4 + 4 + 0 = 8
 *   pop std-dev = sqrt(8 / 3) ≈ 1.6330
 *   CV          = 1.6330 / 8.0 ≈ 0.2041
 *
 * Any change to the formula that produces a different result for this input will
 * break this test and require an explicit documentation update.
 */
@ExtendWith(MockitoExtension.class)
class WorkloadBalanceCalculatorTest {

    static final Instant NOW = Instant.parse("2025-06-15T10:00:00Z");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    JdbcTemplate analyticsJdbc;

    WorkloadBalanceCalculator calculator;

    // Three active technician IDs used throughout
    static final UUID TECH_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID TECH_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID TECH_C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        calculator = new WorkloadBalanceCalculator(analyticsJdbc, clock);
    }

    // ---------------------------------------------------------------
    // Population std-dev formula — convention lock test
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Population std-dev: [10,6,8] → sqrt(8/3) ≈ 1.6330")
    void populationStdDev_knownDataset_matchesHandComputed() {
        double[] values = {10.0, 6.0, 8.0};
        double mean = 8.0;
        double stdDev = WorkloadBalanceCalculator.populationStdDev(values, mean);
        // sqrt(8/3) = 1.63299...
        assertThat(stdDev).isCloseTo(1.6330, within(0.0001));
    }

    @Test
    @DisplayName("Population std-dev: uniform values [8,8,8] → std-dev = 0")
    void populationStdDev_uniformValues_isZero() {
        double[] values = {8.0, 8.0, 8.0};
        double mean = 8.0;
        double stdDev = WorkloadBalanceCalculator.populationStdDev(values, mean);
        assertThat(stdDev).isCloseTo(0.0, within(1e-10));
    }

    @Test
    @DisplayName("CV computed correctly: hours [10,6,8] min → CV ≈ 0.2041")
    void compute_cvArithmetic_handComputedDataset() {
        // 3 active techs
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B, TECH_C));

        // Labour entries: 600 min, 360 min, 480 min → 10h, 6h, 8h
        when(analyticsJdbc.queryForList(
                anyString(),
                any(Timestamp.class)))
                .thenReturn(List.of(
                        Map.of("technician_id", TECH_A, "total_minutes", 600L),
                        Map.of("technician_id", TECH_B, "total_minutes", 360L),
                        Map.of("technician_id", TECH_C, "total_minutes", 480L)
                ));

        // No baseline → BASELINE_PENDING
        when(analyticsJdbc.queryForList(
                anyString(),
                anyString(), anyString()))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        // Find P7D result
        KpiAggregator.KpiAggregatorResult r7 = results.stream()
                .filter(r -> "P7D".equals(r.windowKey()))
                .findFirst().orElseThrow();

        assertThat(r7.value().doubleValue()).isCloseTo(0.2041, within(0.0001));
        assertThat(r7.maturity()).isEqualTo(WorkloadBalanceCalculator.BASELINE_PENDING);
        assertThat(r7.sampleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("metricKey returns the correct stable key")
    void metricKey_correct() {
        assertThat(calculator.metricKey()).isEqualTo("workforce.workload_balance.cv");
    }

    // ---------------------------------------------------------------
    // NOT_MEANINGFUL guards
    // ---------------------------------------------------------------

    @Test
    @DisplayName("2 active technicians → NOT_MEANINGFUL (insufficient sample size)")
    void twoTechnicians_returnsNotMeaningful() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r ->
                WorkloadBalanceCalculator.NOT_MEANINGFUL.equals(r.maturity())
                && r.value() == null);
    }

    @Test
    @DisplayName("1 active technician → NOT_MEANINGFUL")
    void oneTechnician_returnsNotMeaningful() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r -> WorkloadBalanceCalculator.NOT_MEANINGFUL.equals(r.maturity()));
    }

    @Test
    @DisplayName("Zero active technicians → NOT_MEANINGFUL")
    void zeroTechnicians_returnsNotMeaningful() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r -> WorkloadBalanceCalculator.NOT_MEANINGFUL.equals(r.maturity()));
    }

    @Test
    @DisplayName("Mean assigned hours = 0 → NOT_MEANINGFUL (zero-mean guard)")
    void zeroMean_returnsNotMeaningful() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B, TECH_C));

        // No labour entries → all techs have 0 hours → mean = 0
        when(analyticsJdbc.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r ->
                WorkloadBalanceCalculator.NOT_MEANINGFUL.equals(r.maturity())
                && r.value() == null);
    }

    // ---------------------------------------------------------------
    // Guardrail direction
    // ---------------------------------------------------------------

    @Test
    @DisplayName("No baseline row → BASELINE_PENDING")
    void noBaseline_returnsBaselinePending() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B, TECH_C));

        when(analyticsJdbc.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(List.of(
                        Map.of("technician_id", TECH_A, "total_minutes", 480L),
                        Map.of("technician_id", TECH_B, "total_minutes", 480L),
                        Map.of("technician_id", TECH_C, "total_minutes", 480L)
                ));

        when(analyticsJdbc.queryForList(anyString(), anyString(), anyString()))
                .thenReturn(List.of());  // empty → no baseline

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r -> WorkloadBalanceCalculator.BASELINE_PENDING.equals(r.maturity()));
    }

    @Test
    @DisplayName("Current CV higher than baseline → WORSENED")
    void currentCvHigherThanBaseline_returnsWorsened() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B, TECH_C));

        // CV ≈ 0.2041 (hours [10h, 6h, 8h])
        when(analyticsJdbc.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(List.of(
                        Map.of("technician_id", TECH_A, "total_minutes", 600L),
                        Map.of("technician_id", TECH_B, "total_minutes", 360L),
                        Map.of("technician_id", TECH_C, "total_minutes", 480L)
                ));

        // Baseline CV = 0.10 (lower = better balance) → current 0.2041 > 0.10 → WORSENED
        when(analyticsJdbc.queryForList(anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("baseline_value", 0.10)));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r -> WorkloadBalanceCalculator.WORSENED.equals(r.maturity()));
    }

    @Test
    @DisplayName("Current CV lower than baseline → IMPROVED")
    void currentCvLowerThanBaseline_returnsImproved() {
        when(analyticsJdbc.queryForList(eq("SELECT id FROM technician WHERE active = TRUE"), eq(UUID.class)))
                .thenReturn(List.of(TECH_A, TECH_B, TECH_C));

        // CV ≈ 0.2041
        when(analyticsJdbc.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(List.of(
                        Map.of("technician_id", TECH_A, "total_minutes", 600L),
                        Map.of("technician_id", TECH_B, "total_minutes", 360L),
                        Map.of("technician_id", TECH_C, "total_minutes", 480L)
                ));

        // Baseline CV = 0.50 → current 0.2041 < 0.50 → IMPROVED
        when(analyticsJdbc.queryForList(anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("baseline_value", 0.50)));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();
        assertThat(results).allMatch(r -> WorkloadBalanceCalculator.IMPROVED.equals(r.maturity()));
    }

    @Test
    @DisplayName("Uniform hours → CV = 0 (perfectly balanced team)")
    void uniformHours_cvIsZero_stdDevIsZero() {
        double[] hours = {8.0, 8.0, 8.0, 8.0};
        double mean = 8.0;
        double stdDev = WorkloadBalanceCalculator.populationStdDev(hours, mean);
        assertThat(stdDev).isCloseTo(0.0, within(1e-10));
    }
}
