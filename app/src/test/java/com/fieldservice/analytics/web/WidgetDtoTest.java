package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WidgetDto} mapping logic.
 *
 * <p>No Spring context required.
 */
class WidgetDtoTest {

    // ── Staleness clamping ────────────────────────────────────────────────────

    @Test
    @DisplayName("stalenessSeconds is propagated from projection")
    void from_staleness_propagated() {
        KpiProjection p = projection("sla.compliance.rate", false, Instant.now(), 45L);
        WidgetDto dto = WidgetDto.from(p, "THIRTY_DAYS");
        assertThat(dto.stalenessSeconds()).isEqualTo(45L);
    }

    @Test
    @DisplayName("negative stalenessSeconds is clamped to 0")
    void from_staleness_clamped() {
        KpiProjection p = projection("sla.compliance.rate", false, Instant.now(), -5L);
        WidgetDto dto = WidgetDto.from(p, "THIRTY_DAYS");
        assertThat(dto.stalenessSeconds()).isZero();
    }

    // ── Degraded reason mapping ───────────────────────────────────────────────

    @Test
    @DisplayName("degraded=false produces null degradedReason")
    void from_notDegraded_noReason() {
        KpiProjection p = projection("sla.compliance.rate", false, Instant.now(), 10L);
        WidgetDto dto = WidgetDto.from(p, "THIRTY_DAYS");
        assertThat(dto.degraded()).isFalse();
        assertThat(dto.degradedReason()).isNull();
    }

    @Test
    @DisplayName("degraded=true with EPOCH dataAsOf produces NO_DATA reason")
    void from_degraded_epochDataAsOf_noData() {
        KpiProjection p = projection("sla.breach.count", true, Instant.EPOCH, 0L);
        WidgetDto dto = WidgetDto.from(p, "THIRTY_DAYS");
        assertThat(dto.degraded()).isTrue();
        assertThat(dto.degradedReason()).isEqualTo("NO_DATA");
    }

    @Test
    @DisplayName("degraded=true with non-EPOCH dataAsOf produces STALE_FALLBACK reason")
    void from_degraded_staleDataAsOf_staleFallback() {
        KpiProjection p = projection("sla.breach.count", true,
                Instant.parse("2026-07-01T00:00:00Z"), 120L);
        WidgetDto dto = WidgetDto.from(p, "THIRTY_DAYS");
        assertThat(dto.degraded()).isTrue();
        assertThat(dto.degradedReason()).isEqualTo("STALE_FALLBACK");
    }

    // ── Unit derivation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("rate metrics get RATE unit")
    void from_unit_rate() {
        WidgetDto dto = WidgetDto.from(projection("sla.compliance.rate", false, Instant.now(), 0L), "THIRTY_DAYS");
        assertThat(dto.unit()).isEqualTo("RATE");
    }

    @Test
    @DisplayName("count metrics get COUNT unit")
    void from_unit_count() {
        WidgetDto dto = WidgetDto.from(projection("backlog.open.count", false, Instant.now(), 0L), "THIRTY_DAYS");
        assertThat(dto.unit()).isEqualTo("COUNT");
    }

    @Test
    @DisplayName("resolution mean gets MINUTES unit")
    void from_unit_minutes() {
        WidgetDto dto = WidgetDto.from(projection("sla.resolution.mean", false, Instant.now(), 0L), "THIRTY_DAYS");
        assertThat(dto.unit()).isEqualTo("MINUTES");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static KpiProjection projection(String metricKey, boolean degraded,
                                             Instant dataAsOf, long stalenessSeconds) {
        return new KpiProjection(
                UUID.randomUUID(),
                metricKey,
                "ALL",
                "P30D",
                new BigDecimal("0.95"),
                new BigDecimal("855"),
                new BigDecimal("900"),
                900,
                "MATURED",
                dataAsOf,
                3L,
                degraded,
                stalenessSeconds
        );
    }
}
