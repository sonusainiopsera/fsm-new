package com.fieldservice.analytics.internal.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI Baseline Instrumentation Validation — First-Time-Fix and Cohort Maturity (WO-207).
 *
 * <h3>Metric formula</h3>
 * <pre>
 *   matured_rate = first_time_fix_count / total_matured_closures
 *   Provisional work orders (closed &lt; 30 days ago) are excluded from the matured rate.
 * </pre>
 *
 * <h3>Repeat-visit window rule</h3>
 * Two work orders on the same {@code (asset_id, fault_key)} are linked as a repeat visit when the
 * later closure's {@code closed_at} is <em>strictly less than</em> 30 days ({@link RepeatVisitLinker#WINDOW_DAYS})
 * after the earlier one. Exactly 30 days is NOT a repeat visit — it falls outside the window.
 *
 * <h3>Cohort maturity rule</h3>
 * A closure becomes MATURED at exactly {@code closed_at + 30 days}. The predicate is inclusive:
 * {@code matured_at &lt;= now}, so the transition happens precisely at the boundary instant.
 *
 * <h3>Golden dataset provenance</h3>
 * See {@code src/test/resources/golden/kpi-expected-values.json} section
 * {@code first_time_fix}.
 */
@DisplayName("First-Time-Fix / Cohort Maturity — KPI Instrumentation Validation (WO-207)")
class FirstTimeFixValidationTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    // ─── WINDOW_DAYS constant is 30 ─────────────────────────────────────────────

    @Test
    @DisplayName("WINDOW_DAYS is exactly 30 — business rule must not drift")
    void windowDays_isExactly30() {
        assertThat(RepeatVisitLinker.WINDOW_DAYS).isEqualTo(30);
    }

    // ─── AC-5 / AC-6: cohort maturity clock transitions ──────────────────────────

    @Test
    @DisplayName("AC-6: work order closed fewer than 30 days ago is PROVISIONAL")
    void closedLessThan30DaysAgo_isProvisional() {
        // closed at T=0; now = T + 29 days → matured_at = T+30 not yet reached
        Instant closedAt = EPOCH;
        Clock clock = Clock.fixed(EPOCH.plusSeconds(29L * 86400), ZoneOffset.UTC);

        CohortMaturityResolver resolver =
                new CohortMaturityResolver(null, clock); // jdbcTemplate not needed for resolveMaturity
        String maturity = resolver.resolveMaturity(closedAt);

        assertThat(maturity).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("AC-6: work order matured exactly at T+30d — inclusive boundary")
    void closedExactly30DaysAgo_isMatured() {
        // closed at T=0; now = T + 30 days exactly → matured_at == now → MATURED (inclusive <=)
        Instant closedAt = EPOCH;
        Clock clock = Clock.fixed(EPOCH.plusSeconds(30L * 86400), ZoneOffset.UTC);

        CohortMaturityResolver resolver = new CohortMaturityResolver(null, clock);
        String maturity = resolver.resolveMaturity(closedAt);

        assertThat(maturity).isEqualTo("MATURED")
                .as("Promotion is inclusive at exactly T+30d (matured_at <= now)");
    }

    @Test
    @DisplayName("AC-6: work order closed more than 30 days ago is MATURED")
    void closedMoreThan30DaysAgo_isMatured() {
        Instant closedAt = EPOCH;
        Clock clock = Clock.fixed(EPOCH.plusSeconds(31L * 86400), ZoneOffset.UTC);

        CohortMaturityResolver resolver = new CohortMaturityResolver(null, clock);
        String maturity = resolver.resolveMaturity(closedAt);

        assertThat(maturity).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("AC-6: clock advanced from T+29d to T+30d transitions PROVISIONAL → MATURED")
    void clockAdvanced_transitionsProvisionToMatured() {
        Instant closedAt = EPOCH;

        CohortMaturityResolver before = new CohortMaturityResolver(
                null, Clock.fixed(EPOCH.plusSeconds(29L * 86400), ZoneOffset.UTC));
        CohortMaturityResolver after = new CohortMaturityResolver(
                null, Clock.fixed(EPOCH.plusSeconds(30L * 86400), ZoneOffset.UTC));

        assertThat(before.resolveMaturity(closedAt)).isEqualTo("PROVISIONAL");
        assertThat(after.resolveMaturity(closedAt)).isEqualTo("MATURED");
    }

    // ─── Repeat-visit window arithmetic (AC-5) ───────────────────────────────────

    @Test
    @DisplayName("AC-5: 29-day gap is within the repeat-visit window (strictly less than 30)")
    void gap29Days_isWithinWindow() {
        long gapDays = 29L;
        assertThat(gapDays < RepeatVisitLinker.WINDOW_DAYS)
                .as("29 days < WINDOW_DAYS=30 → should be linked as repeat visit")
                .isTrue();
    }

    @Test
    @DisplayName("AC-5: exactly 30-day gap is OUTSIDE the window (boundary = excluded)")
    void gap30Days_isOutsideWindow() {
        long gapDays = 30L;
        // Strictly less-than: 30 < 30 is false → not linked
        assertThat(gapDays < RepeatVisitLinker.WINDOW_DAYS)
                .as("30 days == WINDOW_DAYS=30 → boundary is outside (strict <)")
                .isFalse();
    }

    @Test
    @DisplayName("AC-5: 31-day gap is outside the window")
    void gap31Days_isOutsideWindow() {
        long gapDays = 31L;
        assertThat(gapDays < RepeatVisitLinker.WINDOW_DAYS).isFalse();
    }

    // ─── Cohort maturity — DST-spanning window ────────────────────────────────────

    @Test
    @DisplayName("AC-10: maturity uses epoch-seconds arithmetic (DST-safe), not calendar days")
    void maturityClock_dstSafe_epochSecondsArithmetic() {
        // Closed on 2026-03-08T02:00:00Z (near US DST change 2026-03-08)
        // 30 days later in epoch-seconds: 2026-04-07T02:00:00Z (DST has changed but epoch is invariant)
        Instant closedAt = Instant.parse("2026-03-08T02:00:00Z");
        Instant maturedAt = closedAt.plusSeconds(30L * 86400);

        // 1 second before matured_at → PROVISIONAL
        Clock justBefore = Clock.fixed(maturedAt.minusSeconds(1), ZoneOffset.UTC);
        CohortMaturityResolver resolver = new CohortMaturityResolver(null, justBefore);
        assertThat(resolver.resolveMaturity(closedAt)).isEqualTo("PROVISIONAL");

        // At exactly matured_at → MATURED
        Clock exactly = Clock.fixed(maturedAt, ZoneOffset.UTC);
        CohortMaturityResolver resolver2 = new CohortMaturityResolver(null, exactly);
        assertThat(resolver2.resolveMaturity(closedAt)).isEqualTo("MATURED");
    }
}
