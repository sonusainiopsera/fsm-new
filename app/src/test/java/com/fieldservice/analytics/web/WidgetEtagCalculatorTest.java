package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WidgetEtagCalculator}.
 *
 * <p>No Spring context required — the calculator is a pure function.
 */
class WidgetEtagCalculatorTest {

    private final WidgetEtagCalculator calculator = new WidgetEtagCalculator();

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ETag is deterministic for identical projection state")
    void compute_deterministic() {
        List<KpiProjection> projections = List.of(slaProjection(7));

        String etag1 = calculator.compute(projections);
        String etag2 = calculator.compute(projections);

        assertThat(etag1).isEqualTo(etag2);
    }

    @Test
    @DisplayName("ETag is order-independent — permutation of inputs produces same ETag")
    void compute_orderIndependent() {
        KpiProjection sla      = slaProjection(7);
        KpiProjection backlog  = backlogProjection(7);

        String etagAB = calculator.compute(List.of(sla, backlog));
        String etagBA = calculator.compute(List.of(backlog, sla));

        assertThat(etagAB).isEqualTo(etagBA);
    }

    @Test
    @DisplayName("ETag changes when projection_version is incremented")
    void compute_changesOnVersionBump() {
        KpiProjection v1 = projection("sla.compliance.rate", "ALL", "P30D", 5L, new BigDecimal("0.95"));
        KpiProjection v2 = projection("sla.compliance.rate", "ALL", "P30D", 6L, new BigDecimal("0.95"));

        String etag1 = calculator.compute(List.of(v1));
        String etag2 = calculator.compute(List.of(v2));

        assertThat(etag1).isNotEqualTo(etag2);
    }

    @Test
    @DisplayName("ETag changes when value changes")
    void compute_changesOnValueChange() {
        KpiProjection low  = projection("sla.compliance.rate", "ALL", "P30D", 5L, new BigDecimal("0.80"));
        KpiProjection high = projection("sla.compliance.rate", "ALL", "P30D", 5L, new BigDecimal("0.95"));

        String etagLow  = calculator.compute(List.of(low));
        String etagHigh = calculator.compute(List.of(high));

        assertThat(etagLow).isNotEqualTo(etagHigh);
    }

    @Test
    @DisplayName("ETag for empty list returns constant sentinel")
    void compute_emptyList() {
        String etag = calculator.compute(List.of());

        assertThat(etag).isEqualTo("\"empty\"");
    }

    @Test
    @DisplayName("ETag is a quoted string suitable for HTTP header")
    void compute_quotedFormat() {
        String etag = calculator.compute(List.of(slaProjection(7)));

        assertThat(etag).startsWith("\"").endsWith("\"");
        assertThat(etag).hasSize(18); // 2 quotes + 16 hex chars
    }

    @Test
    @DisplayName("Two independent calculator instances produce the same ETag for same input")
    void compute_instanceIndependent() {
        WidgetEtagCalculator calc2 = new WidgetEtagCalculator();
        List<KpiProjection> projections = List.of(slaProjection(7), backlogProjection(30));

        String etag1 = calculator.compute(projections);
        String etag2 = calc2.compute(projections);

        assertThat(etag1).isEqualTo(etag2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static KpiProjection slaProjection(long version) {
        return projection("sla.compliance.rate", "ALL", "P30D", version, new BigDecimal("0.9500"));
    }

    private static KpiProjection backlogProjection(long version) {
        return projection("backlog.open.count", "ALL", "P30D", version, new BigDecimal("42"));
    }

    private static KpiProjection projection(String metricKey, String segmentKey, String windowKey,
                                             long version, BigDecimal value) {
        return new KpiProjection(
                UUID.randomUUID(),
                metricKey,
                segmentKey,
                windowKey,
                value,
                null,
                null,
                10,
                "MATURED",
                Instant.parse("2026-08-01T00:00:00Z"),
                version,
                false,
                0L
        );
    }
}
