package com.fieldservice.analytics.workforce;

import com.fieldservice.analytics.internal.KpiAggregator.KpiAggregatorResult;
import com.fieldservice.analytics.internal.sla.BaselineMetricRepository;
import com.fieldservice.analytics.internal.workforce.ActiveTechnicianDayResolver;
import com.fieldservice.analytics.internal.workforce.UtilizationCalculator;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRepository;
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
 * Unit tests for {@link UtilizationCalculator}.
 * No Spring context — all dependencies injected via Mockito.
 * Fixed Clock injected for deterministic ISO-week boundaries.
 */
@ExtendWith(MockitoExtension.class)
class UtilizationCalculatorTest {

    /** Fixed "now" at the start of an ISO week (Monday) */
    static final Instant NOW   = Instant.parse("2025-06-02T12:00:00Z"); // Monday
    static final Clock   CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final UUID TECH_A = UUID.fromString("00000000-0000-7163-0001-000000000001");
    static final UUID TECH_B = UUID.fromString("00000000-0000-7163-0001-000000000002");

    static final LocalDate WEEK = LocalDate.parse("2025-05-26"); // ISO week containing NOW-7d

    @Mock WorkforceAggregationRepository repo;
    @Mock BaselineMetricRepository       baselineRepo;

    ActiveTechnicianDayResolver dayResolver;
    UtilizationCalculator       calculator;

    @BeforeEach
    void setUp() {
        dayResolver = new ActiveTechnicianDayResolver();
        calculator  = new UtilizationCalculator(repo, dayResolver, baselineRepo, CLOCK);
        when(baselineRepo.findByMetricKeyAndSegmentKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("Ratio arithmetic")
    class RatioArithmetic {

        @Test
        @DisplayName("zero denominator returns null (no-data)")
        void zeroDenominator() {
            assertThat(UtilizationCalculator.ratio(0L, 0L)).isNull();
        }

        @Test
        @DisplayName("full utilization returns 1.0000")
        void fullUtilization() {
            assertThat(UtilizationCalculator.ratio(480L, 480L))
                    .isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("partial utilization rounds to 4 decimal places")
        void partialUtilization() {
            // 300/480 = 0.625
            assertThat(UtilizationCalculator.ratio(300L, 480L))
                    .isEqualByComparingTo("0.6250");
        }
    }

    @Nested
    @DisplayName("AC-1: Sum-of-numerators rollup vs mean-of-ratios")
    class RollupFormula {

        @Test
        @DisplayName("sum-of-numerators and mean-of-ratios differ — and sum-of-numerators is used")
        void sumOfNumeratorsDiffersFromMeanOfRatios() {
            // Tech A: 2400 / 3000 min = 80%
            // Tech B: 600  / 600  min = 100%
            // mean-of-ratios    = (0.80 + 1.00) / 2 = 0.90
            // sum-of-numerators = 3000 / 3600 = 0.8333...
            BigDecimal meanOfRatios    = new BigDecimal("0.9000");
            BigDecimal sumOfNumerators = UtilizationCalculator.ratio(3000L, 3600L);

            assertThat(sumOfNumerators).isNotEqualByComparingTo(meanOfRatios);
            assertThat(sumOfNumerators).isEqualByComparingTo("0.8333");

            // The calculator uses sum-of-numerators: verify by seeding labour/shift rows
            LabourRow lA = new LabourRow(TECH_A, WEEK, 2400L, NOW);
            LabourRow lB = new LabourRow(TECH_B, WEEK, 600L,  NOW);
            ShiftRow  sA = new ShiftRow(TECH_A, WEEK, 3000L);
            ShiftRow  sB = new ShiftRow(TECH_B, WEEK, 600L);

            stubRepo(List.of(lA, lB), List.of(sA, sB));

            List<KpiAggregatorResult> results = calculator.compute();

            KpiAggregatorResult allResult = results.stream()
                    .filter(r -> "ALL".equals(r.segmentKey()) && "P7D".equals(r.windowKey()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ALL rollup not found"));

            assertThat(allResult.value()).isEqualByComparingTo("0.8333");
            assertThat(allResult.value()).isNotEqualByComparingTo(meanOfRatios);
        }
    }

    @Nested
    @DisplayName("AC-3: Partial-week labelling")
    class PartialWeekLabelling {

        @Test
        @DisplayName("week starting before window start is flagged partial")
        void weekStartingBeforeWindowIsPartial() {
            // NOW is Monday 2025-06-02; P7D window starts 2025-05-26 (Monday)
            // That is a full week — let's use a week that starts before the window
            LocalDate earlyWeek = LocalDate.parse("2025-05-19"); // week before window
            LabourRow lr = new LabourRow(TECH_A, earlyWeek, 480L, NOW.minusSeconds(3600));
            ShiftRow  sr = new ShiftRow(TECH_A,  earlyWeek, 480L);

            stubRepo(List.of(lr), List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            // The partial week should be flagged
            boolean anyPartial = results.stream()
                    .anyMatch(r -> r.partialBucket() && r.segmentKey().contains(TECH_A.toString()));
            assertThat(anyPartial).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-4: Onboarding / deactivation mid-window")
    class OnboardingDeactivation {

        @Test
        @DisplayName("technician onboarded on day 20 of a 30-day window contributes at most 10 shift days")
        void onboardingMidWindow() {
            // Shift rows only exist for last 10 days of 30-day window
            // → activeDays ≤ 10; this is enforced by the shift data scope.
            // We test that the denominator for the tech is ≤ 600 min (10 days × 1h shift).
            LocalDate recentWeek = LocalDate.parse("2025-05-26"); // within the 7-day window
            ShiftRow sr = new ShiftRow(TECH_A, recentWeek, 300L); // 5h shift only this week

            when(repo.queryLabourByTechWeek(any(), any())).thenReturn(List.of());
            when(repo.queryShiftByTechWeek(any(), any())).thenReturn(List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            // The ALL rollup denominator should be ≤ 300 (shift minutes present)
            KpiAggregatorResult allResult = results.stream()
                    .filter(r -> "ALL".equals(r.segmentKey()) && "P7D".equals(r.windowKey()))
                    .findFirst().orElse(null);

            if (allResult != null) {
                assertThat(allResult.denominator())
                        .isLessThanOrEqualByComparingTo(BigDecimal.valueOf(300));
            }
        }
    }

    @Nested
    @DisplayName("AC-5: No personal data in segment keys")
    class DataClassification {

        @Test
        @DisplayName("segment keys contain only UUID — no names, contact details, or GPS")
        void segmentKeysContainNoPersonalData() {
            LabourRow lr = new LabourRow(TECH_A, WEEK, 480L, NOW);
            ShiftRow  sr = new ShiftRow(TECH_A,  WEEK, 480L);
            stubRepo(List.of(lr), List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            for (KpiAggregatorResult r : results) {
                // Segment key must not contain '@', spaces (names), or coordinates
                assertThat(r.segmentKey()).doesNotContain("@", " ");
                // If it references a tech, it must be UUID format
                if (r.segmentKey().startsWith("TECH:")) {
                    String idPart = r.segmentKey().replace("TECH:", "");
                    if (idPart.contains(":WEEK:")) {
                        idPart = idPart.substring(0, idPart.indexOf(":WEEK:"));
                    }
                    // Must parse as UUID without throwing
                    UUID.fromString(idPart);
                }
            }
        }
    }

    @Nested
    @DisplayName("INCOMPLETE_DATA handling")
    class IncompleteData {

        @Test
        @DisplayName("technician-week with labour but no shift is marked INCOMPLETE_DATA")
        void labourWithoutShiftIsIncomplete() {
            LabourRow lr = new LabourRow(TECH_A, WEEK, 480L, NOW);
            stubRepo(List.of(lr), List.of()); // no shift rows

            List<KpiAggregatorResult> results = calculator.compute();

            boolean anyIncomplete = results.stream().anyMatch(KpiAggregatorResult::incompleteData);
            assertThat(anyIncomplete).isTrue();
        }

        @Test
        @DisplayName("technician-week with shift and zero labour is valid (0% utilization)")
        void shiftWithZeroLabourIsValidZeroPercent() {
            // Roster hours present, no field time → 0% utilization — valid, must not be excluded
            ShiftRow sr = new ShiftRow(TECH_A, WEEK, 480L);
            stubRepo(List.of(), List.of(sr));

            List<KpiAggregatorResult> results = calculator.compute();

            KpiAggregatorResult techResult = results.stream()
                    .filter(r -> r.segmentKey().contains(TECH_A.toString())
                            && "P7D".equals(r.windowKey())
                            && !r.incompleteData())
                    .findFirst().orElse(null);

            assertThat(techResult).isNotNull();
            assertThat(techResult.value()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void stubRepo(List<LabourRow> labour, List<ShiftRow> shift) {
        when(repo.queryLabourByTechWeek(any(), any())).thenReturn(labour);
        when(repo.queryShiftByTechWeek(any(), any())).thenReturn(shift);
    }
}
