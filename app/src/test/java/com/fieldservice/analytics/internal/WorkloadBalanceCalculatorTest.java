package com.fieldservice.analytics.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkloadBalanceCalculator} (WO-165, AC-2, AC-4).
 *
 * <p>Hand-computed reference values are verified to lock the population-CV formula.
 *
 * <h3>Reference dataset A (3 technicians: 10h, 10h, 10h)</h3>
 * mean=10, pop_stddev=0, CV=0.0000
 *
 * <h3>Reference dataset B (4 technicians: 4h, 6h, 10h, 20h)</h3>
 * total_minutes: 240, 360, 600, 1200
 * hours: 4.0, 6.0, 10.0, 20.0
 * mean = 40/4 = 10.0
 * pop_stddev = SQRT(((4-10)^2 + (6-10)^2 + (10-10)^2 + (20-10)^2) / 4)
 *            = SQRT((36 + 16 + 0 + 100) / 4)
 *            = SQRT(152 / 4)
 *            = SQRT(38)
 *            ≈ 6.1644
 * CV = 6.1644 / 10.0 ≈ 0.6164
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkloadBalanceCalculator unit tests")
class WorkloadBalanceCalculatorTest {

    @Mock
    private BacklogAggregationRepository repository;

    private WorkloadBalanceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new WorkloadBalanceCalculator(repository);
    }

    @Test
    @DisplayName("AC-2: perfectly balanced team (all same hours) → CV = 0")
    void computeCv_equalHours_cvIsZero() {
        stubHours(List.of(600L, 600L, 600L)); // 10h each

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isTrue();
        assertThat(result.cv().doubleValue()).isEqualTo(0.0);
        assertThat(result.technicianCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-2: reference dataset B → CV ≈ 0.6164 (population stddev)")
    void computeCv_referenceDatasetB_matchesHandComputed() {
        stubHours(List.of(240L, 360L, 600L, 1200L)); // 4h, 6h, 10h, 20h

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isTrue();
        // CV = SQRT(38) / 10 ≈ 0.6164
        assertThat(result.cv().doubleValue()).isCloseTo(0.6164, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.technicianCount()).isEqualTo(4);
        assertThat(result.meanHoursPerTechnician().doubleValue()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("AC-4: fewer than 3 technicians → NOT_MEANINGFUL")
    void computeCv_twoTechnicians_notMeaningful() {
        stubHours(List.of(300L, 600L)); // only 2 technicians

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isFalse();
        assertThat(result.reason()).isEqualTo(WorkloadBalanceCalculator.NotMeaningfulReason.TOO_FEW_TECHNICIANS);
    }

    @Test
    @DisplayName("AC-4: single technician → NOT_MEANINGFUL")
    void computeCv_singleTechnician_notMeaningful() {
        stubHours(List.of(480L)); // only 1 technician

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isFalse();
        assertThat(result.reason()).isEqualTo(WorkloadBalanceCalculator.NotMeaningfulReason.TOO_FEW_TECHNICIANS);
    }

    @Test
    @DisplayName("AC-4: zero mean assigned hours → NOT_MEANINGFUL (no division by zero)")
    void computeCv_zeroMean_notMeaningful() {
        stubHours(List.of(0L, 0L, 0L)); // 3 technicians with zero hours

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isFalse();
        assertThat(result.reason()).isEqualTo(WorkloadBalanceCalculator.NotMeaningfulReason.ZERO_MEAN);
    }

    @Test
    @DisplayName("AC-4: no active technicians → NOT_MEANINGFUL")
    void computeCv_noTechnicians_notMeaningful() {
        when(repository.queryTechnicianHoursInWindow(anyInt())).thenReturn(List.of());

        WorkloadBalanceCalculator.CvResult result = calculator.computeCv(7);

        assertThat(result.meaningful()).isFalse();
        assertThat(result.reason()).isEqualTo(WorkloadBalanceCalculator.NotMeaningfulReason.NO_ACTIVE_TECHNICIANS);
    }

    @Test
    @DisplayName("AC-2: toAggregateResult returns null for NOT_MEANINGFUL result")
    void toAggregateResult_notMeaningful_returnsNull() {
        WorkloadBalanceCalculator.CvResult notMeaningful =
                WorkloadBalanceCalculator.CvResult.notMeaningful(
                        WorkloadBalanceCalculator.NotMeaningfulReason.ZERO_MEAN);

        assertThat(calculator.toAggregateResult(notMeaningful)).isNull();
    }

    // -------------------------------------------------------------------------

    private void stubHours(List<Long> minutesList) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long minutes : minutesList) {
            rows.add(Map.of("technician_id", UUID.randomUUID(), "total_minutes", minutes));
        }
        when(repository.queryTechnicianHoursInWindow(anyInt())).thenReturn(rows);
    }
}
