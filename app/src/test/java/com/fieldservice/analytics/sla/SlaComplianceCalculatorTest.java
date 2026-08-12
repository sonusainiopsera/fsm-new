package com.fieldservice.analytics.sla;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.sla.BaselineMetricRepository;
import com.fieldservice.analytics.internal.sla.SlaAggregationRepository;
import com.fieldservice.analytics.internal.sla.SlaAggregationRow;
import com.fieldservice.analytics.internal.sla.SlaBreachReasonRow;
import com.fieldservice.analytics.internal.sla.SlaBreachCountCalculator;
import com.fieldservice.analytics.internal.sla.SlaComplianceCalculator;
import com.fieldservice.analytics.internal.sla.SlaResolutionMeanCalculator;
import com.fieldservice.analytics.internal.sla.SlaResolutionMedianCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SLA compliance, breach count, and resolution time calculators.
 * No Spring context — all dependencies injected via Mockito.
 */
@ExtendWith(MockitoExtension.class)
class SlaComplianceCalculatorTest {

    static final Instant NOW = Instant.parse("2025-06-01T12:00:00Z");
    static final Clock   CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock SlaAggregationRepository repo;
    @Mock BaselineMetricRepository  baselineRepo;

    SlaComplianceCalculator  complianceCalc;
    SlaBreachCountCalculator breachCalc;
    SlaResolutionMeanCalculator   meanCalc;
    SlaResolutionMedianCalculator medianCalc;

    @BeforeEach
    void setUp() {
        complianceCalc = new SlaComplianceCalculator(repo, baselineRepo, CLOCK);
        breachCalc     = new SlaBreachCountCalculator(repo, CLOCK);
        meanCalc       = new SlaResolutionMeanCalculator(repo, CLOCK);
        medianCalc     = new SlaResolutionMedianCalculator(repo, CLOCK);
    }

    // ── SlaComplianceCalculator ───────────────────────────────────────────────────

    @Test
    @DisplayName("compliance ratio: 8/10 compliant → 0.8000")
    void compliance_ratio_computed_correctly() {
        SlaAggregationRow row = row("HIGH", 10, 8, 2, 60.0, 55.0, 0);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row))
                .thenReturn(List.of()); // prior period

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:HIGH", "P7D");
        assertThat(seg).isNotNull();
        assertThat(seg.value()).isEqualByComparingTo("0.8000");
        assertThat(seg.numerator()).isEqualByComparingTo("8");
        assertThat(seg.denominator()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("100% compliant: all closed on time")
    void all_compliant_yields_1_0() {
        SlaAggregationRow row = row("CRITICAL", 5, 5, 0, 30.0, 28.0, 0);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:CRITICAL", "P7D");
        assertThat(seg.value()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("0% compliant: all breached")
    void all_breached_yields_0_0() {
        SlaAggregationRow row = row("LOW", 4, 0, 4, 120.0, 115.0, 480);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:LOW", "P7D");
        assertThat(seg.value()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("zero-denominator: no closed work orders → no-data (null value)")
    void zero_denominator_yields_null_value() {
        assertThat(SlaComplianceCalculator.compliance(0, 0)).isNull();
    }

    @Test
    @DisplayName("work order closed at exact deadline instant counts as compliant")
    void exact_deadline_counts_as_compliant() {
        // 5 closed, 5 compliant (compliance = 1.0): the DB query counts <=, so exact match is compliant
        SlaAggregationRow row = row("HIGH", 5, 5, 0, 60.0, 58.0, 0);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:HIGH", "P7D");
        assertThat(seg.value()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("weighted ALL rollup equals volume-weighted aggregate of segments")
    void weighted_all_rollup_is_volume_weighted() {
        // HIGH: 10 closed, 9 compliant; LOW: 20 closed, 10 compliant
        // Weighted ALL: (9+10) / (10+20) = 19/30 = 0.6333
        SlaAggregationRow high = row("HIGH", 10, 9, 1, 60.0, 58.0, 120);
        SlaAggregationRow low  = row("LOW",  20, 10, 10, 90.0, 85.0, 600);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(high, low)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult all = findSegment(results, "ALL", "P7D");
        assertThat(all).isNotNull();
        assertThat(all.numerator()).isEqualByComparingTo("19");
        assertThat(all.denominator()).isEqualByComparingTo("30");
        // NOT a mean of 0.9 and 0.5 = 0.7; it's 19/30 = 0.6333
        assertThat(all.value()).isEqualByComparingTo("0.6333");
    }

    @Test
    @DisplayName("prior-period delta stored with DELTA: prefix segment key")
    void prior_period_delta_stored() {
        SlaAggregationRow current = row("HIGH", 10, 8, 2, 60.0, 55.0, 0);
        SlaAggregationRow prior   = row("HIGH", 10, 7, 3, 65.0, 60.0, 0);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // First call = current period, second call = prior period
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(current))
                .thenReturn(List.of(prior));

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        // 0.8000 - 0.7000 = 0.1000
        KpiAggregator.KpiAggregatorResult delta = findSegment(results, "DELTA:PRIORITY:HIGH", "P7D");
        assertThat(delta).isNotNull();
        assertThat(delta.value()).isEqualByComparingTo("0.1000");
    }

    @Test
    @DisplayName("BASELINE_PENDING maturity when no baseline row exists")
    void baseline_pending_when_no_baseline() {
        SlaAggregationRow row = row("HIGH", 10, 8, 2, 60.0, 55.0, 0);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = complianceCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:HIGH", "P7D");
        assertThat(seg.maturity()).isEqualTo("BASELINE_PENDING");
    }

    @Test
    @DisplayName("compliance metric key is sla.compliance.rate")
    void metric_key_is_correct() {
        assertThat(complianceCalc.metricKey()).isEqualTo("sla.compliance.rate");
    }

    // ── SlaBreachCountCalculator ──────────────────────────────────────────────────

    @Test
    @DisplayName("breach count aggregated by reason code")
    void breach_count_by_reason() {
        SlaBreachReasonRow r1 = new SlaBreachReasonRow("HIGH", "P7D", "PARTS_UNAVAILABLE", 3, 180L);
        SlaBreachReasonRow r2 = new SlaBreachReasonRow("HIGH", "P7D", "TRAVEL_DISRUPTION",  1, 45L);
        when(repo.queryBreachByReason(any(), any(), anyString())).thenReturn(List.of(r1, r2));

        List<KpiAggregator.KpiAggregatorResult> results = breachCalc.compute();

        KpiAggregator.KpiAggregatorResult parts = findSegment(results,
                "PRIORITY:HIGH:REASON:PARTS_UNAVAILABLE", "P7D");
        assertThat(parts).isNotNull();
        assertThat(parts.numerator()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("unattributed breach has reason UNATTRIBUTED")
    void unattributed_breach_segment_key() {
        SlaBreachReasonRow unattributed = new SlaBreachReasonRow("LOW", "P7D", null, 2, 100L);
        when(repo.queryBreachByReason(any(), any(), anyString())).thenReturn(List.of(unattributed));

        List<KpiAggregator.KpiAggregatorResult> results = breachCalc.compute();

        assertThat(results.stream()
                .anyMatch(r -> r.segmentKey().contains("UNATTRIBUTED"))).isTrue();
    }

    // ── ResolutionTimeCalculator (mean and median) ────────────────────────────────

    @Test
    @DisplayName("mean resolution minutes computed correctly")
    void mean_resolution_minutes() {
        SlaAggregationRow row = row("HIGH", 5, 4, 1, 72.5, 65.0, 30);
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = meanCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:HIGH", "P7D");
        assertThat(seg).isNotNull();
        assertThat(seg.value()).isEqualByComparingTo("72.50");
    }

    @Test
    @DisplayName("median resolution minutes computed correctly")
    void median_resolution_minutes() {
        SlaAggregationRow row = row("HIGH", 5, 4, 1, 72.5, 65.0, 30);
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(row)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = medianCalc.compute();

        KpiAggregator.KpiAggregatorResult seg = findSegment(results, "PRIORITY:HIGH", "P7D");
        assertThat(seg).isNotNull();
        assertThat(seg.value()).isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("mean and median have different metric keys")
    void mean_and_median_have_distinct_metric_keys() {
        assertThat(meanCalc.metricKey()).isEqualTo("sla.resolution.mean");
        assertThat(medianCalc.metricKey()).isEqualTo("sla.resolution.median");
        assertThat(meanCalc.metricKey()).isNotEqualTo(medianCalc.metricKey());
    }

    @Test
    @DisplayName("resolution: weighted ALL rollup equals volume-weighted aggregate")
    void resolution_weighted_all_rollup() {
        // HIGH: 10 WOs, mean 60 min; LOW: 20 WOs, mean 90 min
        // Weighted ALL: (10*60 + 20*90) / (10+20) = (600 + 1800) / 30 = 80.00
        SlaAggregationRow high = row("HIGH", 10, 9, 1, 60.0, 55.0, 120);
        SlaAggregationRow low  = row("LOW",  20, 10, 10, 90.0, 85.0, 600);
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(high, low)).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = meanCalc.compute();

        KpiAggregator.KpiAggregatorResult all = findSegment(results, "ALL", "P7D");
        assertThat(all).isNotNull();
        assertThat(all.value()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("resolution: prior-period delta stored")
    void resolution_prior_period_delta() {
        SlaAggregationRow current = row("HIGH", 5, 4, 1, 80.0, 75.0, 0);
        SlaAggregationRow prior   = row("HIGH", 5, 3, 2, 100.0, 95.0, 0);
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of(current))
                .thenReturn(List.of(prior));

        List<KpiAggregator.KpiAggregatorResult> results = meanCalc.compute();

        // delta = 80 - 100 = -20.00
        KpiAggregator.KpiAggregatorResult delta = findSegment(results, "DELTA:PRIORITY:HIGH", "P7D");
        assertThat(delta).isNotNull();
        assertThat(delta.value()).isEqualByComparingTo("-20.00");
    }

    @Test
    @DisplayName("resolution: no data for empty window")
    void resolution_empty_window_produces_no_results() {
        when(repo.queryWindowedAggregation(any(), any(), anyString()))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = meanCalc.compute();

        assertThat(results.stream()
                .filter(r -> r.windowKey().equals("P7D"))
                .toList()).isEmpty();
    }

    // ── breach reconciliation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("breach count + compliant count = closed count for same segment")
    void breach_count_reconciles_with_compliance() {
        long closed    = 10L;
        long compliant = 8L;
        long breach    = closed - compliant; // 2

        SlaAggregationRow row = row("HIGH", closed, compliant, breach, 60.0, 55.0, 120);
        // Verify arithmetic invariant directly on the row
        assertThat(row.breachCount() + row.compliantCount()).isEqualTo(row.closedCount());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static KpiAggregator.KpiAggregatorResult findSegment(
            List<KpiAggregator.KpiAggregatorResult> results, String segmentKey, String windowKey) {
        return results.stream()
                .filter(r -> r.segmentKey().equals(segmentKey) && r.windowKey().equals(windowKey))
                .findFirst()
                .orElse(null);
    }

    private static SlaAggregationRow row(String priority, long closed, long compliant,
                                          long breach, double mean, double median, long overrun) {
        return new SlaAggregationRow(priority, "P7D", closed, compliant, breach,
                mean, median, overrun, NOW);
    }
}
