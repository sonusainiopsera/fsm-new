package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WidgetEtagCalculator} (WO-166).
 *
 * <p>Validates:
 * <ul>
 *   <li>Determinism: two independently constructed calculators produce identical ETags for the same input.</li>
 *   <li>Change detection: any mutation of version, value, or timestamp yields a different ETag.</li>
 *   <li>Order independence: input ordering does not affect the ETag.</li>
 *   <li>Empty list: produces a stable (non-null, non-blank) ETag.</li>
 *   <li>RFC 7232 format: result is enclosed in double quotes.</li>
 * </ul>
 */
class WidgetEtagCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-08-12T10:00:00Z");

    private WidgetEtagCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new WidgetEtagCalculator();
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    void twoInstancesProduceIdenticalETagForSameInput() {
        List<KpiProjection> projections = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.9200"), T0));

        WidgetEtagCalculator a = new WidgetEtagCalculator();
        WidgetEtagCalculator b = new WidgetEtagCalculator();

        assertThat(a.compute(projections)).isEqualTo(b.compute(projections));
    }

    @Test
    void sameInputProducesSameETagOnRepeatedCalls() {
        List<KpiProjection> projections = List.of(projection("backlog.open.count", "ALL", "ROLLING_30D", 5L, new BigDecimal("42"), T0));

        String first  = calculator.compute(projections);
        String second = calculator.compute(projections);

        assertThat(first).isEqualTo(second);
    }

    // ── Change detection ──────────────────────────────────────────────────────

    @Test
    void differentProjectionVersionYieldsDifferentETag() {
        List<KpiProjection> v1 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.92"), T0));
        List<KpiProjection> v2 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 2L, new BigDecimal("0.92"), T0));

        assertThat(calculator.compute(v1)).isNotEqualTo(calculator.compute(v2));
    }

    @Test
    void differentValueYieldsDifferentETag() {
        List<KpiProjection> p1 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.92"), T0));
        List<KpiProjection> p2 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.91"), T0));

        assertThat(calculator.compute(p1)).isNotEqualTo(calculator.compute(p2));
    }

    @Test
    void differentDataAsOfYieldsDifferentETag() {
        Instant t1 = T0;
        Instant t2 = T0.plusSeconds(1);
        List<KpiProjection> p1 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.92"), t1));
        List<KpiProjection> p2 = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.92"), t2));

        assertThat(calculator.compute(p1)).isNotEqualTo(calculator.compute(p2));
    }

    @Test
    void nullValueDiffersFromZeroValue() {
        List<KpiProjection> nullVal = List.of(projection("backlog.open.count", "ALL", "ROLLING_30D", 1L, null, T0));
        List<KpiProjection> zeroVal = List.of(projection("backlog.open.count", "ALL", "ROLLING_30D", 1L, BigDecimal.ZERO, T0));

        assertThat(calculator.compute(nullVal)).isNotEqualTo(calculator.compute(zeroVal));
    }

    // ── Order independence ────────────────────────────────────────────────────

    @Test
    void inputOrderDoesNotAffectETag() {
        KpiProjection p1 = projection("sla.compliance.rate",   "ALL", "ROLLING_7D",  1L, new BigDecimal("0.92"), T0);
        KpiProjection p2 = projection("backlog.open.count",    "ALL", "ROLLING_30D", 3L, new BigDecimal("55"),   T0);

        assertThat(calculator.compute(List.of(p1, p2)))
                .isEqualTo(calculator.compute(List.of(p2, p1)));
    }

    // ── Empty list ────────────────────────────────────────────────────────────

    @Test
    void emptyListProducesStableNonBlankETag() {
        String etag = calculator.compute(List.of());

        assertThat(etag).isNotBlank();
        assertThat(calculator.compute(List.of())).isEqualTo(etag);
    }

    // ── RFC 7232 format ───────────────────────────────────────────────────────

    @Test
    void etagIsEnclosedInDoubleQuotes() {
        List<KpiProjection> projections = List.of(projection("sla.compliance.rate", "ALL", "ROLLING_7D", 1L, new BigDecimal("0.92"), T0));

        String etag = calculator.compute(projections);

        assertThat(etag).startsWith("\"").endsWith("\"");
    }

    // ── Allow-list validation covered by enum binding (no calculator test needed) ──

    // ── Staleness clamped at zero ─────────────────────────────────────────────

    @Test
    void projectionsWithSameVersionButDifferentStalenessProduceSameETag() {
        // stalenessSeconds is NOT part of the hash (it changes every second) — hash is over version/value/dataAsOf
        KpiProjection fresh = new KpiProjection(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                new BigDecimal("0.92"), null, null, 10, "CURRENT", T0,
                1L, false, null, 0L);
        KpiProjection stale = new KpiProjection(
                "sla.compliance.rate", "ALL", "ROLLING_7D",
                new BigDecimal("0.92"), null, null, 10, "CURRENT", T0,
                1L, false, null, 120L);

        assertThat(calculator.compute(List.of(fresh)))
                .isEqualTo(calculator.compute(List.of(stale)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static KpiProjection projection(
            String metricKey, String segmentKey, String windowKey,
            long version, BigDecimal value, Instant dataAsOf) {
        return new KpiProjection(
                metricKey, segmentKey, windowKey,
                value, null, null, 0, "CURRENT",
                dataAsOf, version, false, null, 0L);
    }
}
