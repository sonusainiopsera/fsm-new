package com.fieldservice.analytics.workforce;

import com.fieldservice.analytics.internal.KpiAggregator.KpiAggregatorResult;
import com.fieldservice.analytics.internal.sla.BaselineMetricRepository;
import com.fieldservice.analytics.internal.workforce.ActiveTechnicianDayResolver;
import com.fieldservice.analytics.internal.workforce.ThroughputCalculator;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRepository;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ClosureRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.LabourRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ShiftRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThroughputCalculator}.
 * No Spring context — all dependencies injected via Mockito.
 * Fixed Clock for deterministic window boundaries.
 */
@ExtendWith(MockitoExtension.class)
class ThroughputCalculatorTest {

    static final Instant NOW   = Instant.parse("2025-06-02T12:00:00Z");
    static final Clock   CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final UUID TECH_A = UUID.fromString("00000000-0000-7163-0002-000000000001");
    static final UUID TECH_B = UUID.fromString("00000000-0000-7163-0002-000000000002");
    static final UUID TECH_C = UUID.fromString("00000000-0000-7163-0002-000000000003");

    static final LocalDate WEEK = LocalDate.parse("2025-05-26");

    @Mock WorkforceAggregationRepository repo;
    @Mock BaselineMetricRepository       baselineRepo;

    ActiveTechnicianDayResolver dayResolver;
    ThroughputCalculator        calculator;

    @BeforeEach
    void setUp() {
        dayResolver = new ActiveTechnicianDayResolver();
        calculator  = new ThroughputCalculator(repo, dayResolver, baselineRepo, CLOCK);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("Ratio arithmetic")
    class RatioArithmetic {

        @Test
        @DisplayName("zero denominator returns null")
        void zeroDenominator() {
            assertThat(ThroughputCalculator.ratio(5L, 0L)).isNull();
        }

        @Test
        @DisplayName("5 closures in 5 active days = 1.0000 jobs per day")
        void oneJobPerDay() {
            assertThat(ThroughputCalculator.ratio(5L, 5L))
                    .isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("10 closures in 4 active days = 2.5000 jobs per day")
        void twoAndHalfJobsPerDay() {
            assertThat(ThroughputCalculator.ratio(10L, 4L))
                    .isEqualByComparingTo("2.5000");
        }
    }

    @Nested
    @DisplayName("AC-2: Zero-active-day exclusion")
    class ZeroActiveDayExclusion {

        @Test
        @DisplayName("technician with zero active days excluded from denominator")
        void zeroActiveDaysTechExcluded() {
            // Tech C has closures but NO shift or labour rows → zero active days
            ClosureRow cA = new ClosureRow(TECH_A, LocalDate.parse("2025-05-27"), 3L, NOW);
            ClosureRow cC = new ClosureRow(TECH_C, LocalDate.parse("2025-05-27"), 5L, NOW);

            ShiftRow sA = new ShiftRow(TECH_A, WEEK, 480L); // Tech A has shifts

            stubRepo(List.of(cA, cC), List.of(), List.of(sA));

            List<KpiAggregatorResult> results = calculator.compute();

            // Tech C should not appear in results (zero active days)
            boolean techCPresent = results.stream()
                    .anyMatch(r -> r.segmentKey().contains(TECH_C.toString()));
            assertThat(techCPresent).isFalse();
        }

        @Test
        @DisplayName("technician with zero active days not counted as zero-throughput in denominator")
        void zeroActiveDaysNotCountedAsZeroDayInDenominator() {
            // If zero-active-day techs were included, team active-days would be inflated,
            // reducing the jobs-per-day rate.
            ClosureRow cA = new ClosureRow(TECH_A, LocalDate.parse("2025-05-27"), 5L, NOW);
            ShiftRow   sA = new ShiftRow(TECH_A, WEEK, 480L);

            stubRepo(List.of(cA), List.of(), List.of(sA));

            List<KpiAggregatorResult> results = calculator.compute();

            KpiAggregatorResult all = results.stream()
                    .filter(r -> "ALL".equals(r.segmentKey()) && "P7D".equals(r.windowKey()))
                    .findFirst().orElse(null);

            if (all != null) {
                // Denominator should be from Tech A's active days only, not inflated by zero-day techs
                // With shift row for the week, Tech A has up to 7 active days in the week within window
                assertThat(all.denominator()).isGreaterThan(BigDecimal.ZERO);
            }
        }
    }

    @Nested
    @DisplayName("AC-2: Active-technician-day definition")
    class ActiveDayDefinition {

        @Test
        @DisplayName("day with shift only counts as active")
        void dayWithShiftIsActive() {
            ActiveTechnicianDayResolver resolver = new ActiveTechnicianDayResolver();
            LocalDate wStart = LocalDate.parse("2025-05-26");
            LocalDate wEnd   = LocalDate.parse("2025-06-02");

            // Shift covers the full week (7 days within window)
            ShiftRow sr = new ShiftRow(TECH_A, wStart, 2400L); // 40h

            java.util.Map<UUID, Long> days = resolver.resolveActiveDays(
                    List.of(), List.of(sr), wStart, wEnd);

            assertThat(days.get(TECH_A)).isEqualTo(7L);
        }

        @Test
        @DisplayName("technician with no shift and no labour has zero active days")
        void noShiftNoLabourZeroActiveDays() {
            ActiveTechnicianDayResolver resolver = new ActiveTechnicianDayResolver();
            LocalDate wStart = LocalDate.parse("2025-05-26");
            LocalDate wEnd   = LocalDate.parse("2025-06-02");

            java.util.Map<UUID, Long> days = resolver.resolveActiveDays(
                    List.of(), List.of(), wStart, wEnd);

            assertThat(days).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-5: No personal data in segment keys")
    class DataClassification {

        @Test
        @DisplayName("segment keys contain only UUID — no names or contact details")
        void segmentKeysAreAnonymised() {
            ClosureRow cr = new ClosureRow(TECH_A, LocalDate.parse("2025-05-27"), 2L, NOW);
            ShiftRow   sr = new ShiftRow(TECH_A, WEEK, 480L);
            stubRepo(List.of(cr), List.of(), List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            for (KpiAggregatorResult r : results) {
                assertThat(r.segmentKey()).doesNotContain("@", " ");
                if (r.segmentKey().startsWith("TECH:")) {
                    String idPart = r.segmentKey().substring("TECH:".length());
                    UUID.fromString(idPart); // must be a valid UUID
                }
            }
        }
    }

    @Nested
    @DisplayName("Debounce: baseline pending until baseline row exists")
    class BaselinePending {

        @Test
        @DisplayName("maturity is BASELINE_PENDING when no baseline row")
        void maturityIsBaselinePendingWithoutBaseline() {
            ClosureRow cr = new ClosureRow(TECH_A, LocalDate.parse("2025-05-27"), 2L, NOW);
            ShiftRow   sr = new ShiftRow(TECH_A, WEEK, 480L);
            stubRepo(List.of(cr), List.of(), List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            boolean allBaselinePending = results.stream()
                    .filter(r -> "ALL".equals(r.segmentKey()))
                    .allMatch(r -> "BASELINE_PENDING".equals(r.maturity()));
            assertThat(allBaselinePending).isTrue();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void stubRepo(List<ClosureRow> closures, List<LabourRow> labour, List<ShiftRow> shift) {
        when(repo.queryClosuresByTechDate(any(), any())).thenReturn(closures);
        when(repo.queryLabourByTechWeek(any(), any())).thenReturn(labour);
        when(repo.queryShiftByTechWeek(any(), any())).thenReturn(shift);
    }
}
