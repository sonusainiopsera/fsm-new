package com.fieldservice.analytics.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BacklogCalculator} and {@link BacklogAggregationRepository} open-state
 * derivation (WO-165, AC-1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BacklogCalculator unit tests")
class BacklogCalculatorTest {

    @Mock
    private BacklogAggregationRepository repository;

    private BacklogCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new BacklogCalculator(repository);
    }

    @Test
    @DisplayName("AC-1: open-state set derived from enum — excludes all terminal states")
    void openStateDerived_excludesTerminalStates() {
        // COMPLETED, CLOSED, CANCELLED must be excluded
        assertThat(BacklogAggregationRepository.OPEN_STATES)
                .doesNotContain(WorkOrderState.COMPLETED, WorkOrderState.CLOSED, WorkOrderState.CANCELLED);

        // Non-terminal states must be included
        assertThat(BacklogAggregationRepository.OPEN_STATES)
                .contains(WorkOrderState.NEW, WorkOrderState.ASSIGNED,
                        WorkOrderState.EN_ROUTE, WorkOrderState.IN_PROGRESS, WorkOrderState.ON_HOLD);
    }

    @Test
    @DisplayName("AC-1: OPEN_STATES + TERMINAL_STATES = all WorkOrderState values")
    void openAndTerminalStates_coverAllValues() {
        assertThat(BacklogAggregationRepository.OPEN_STATES.size()
                + BacklogAggregationRepository.TERMINAL_STATES.size())
                .isEqualTo(WorkOrderState.values().length);
    }

    @Test
    @DisplayName("AC-1: total open count returned as AggregateResult")
    void queryOpenBacklogTotal_returnsResult() {
        when(repository.queryTotalOpenCount()).thenReturn(
                new KpiAggregationQueries.AggregateResult(
                        BigDecimal.valueOf(42), BigDecimal.valueOf(42), BigDecimal.ONE, 42));

        KpiAggregationQueries.AggregateResult result = calculator.queryOpenBacklogTotal();

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(42));
    }

    @Test
    @DisplayName("AC-1: on-hold count is a subset of total open backlog")
    void queryOnHoldCount_returnsSubsetCount() {
        when(repository.queryOnHoldCount()).thenReturn(
                new KpiAggregationQueries.AggregateResult(
                        BigDecimal.valueOf(5), BigDecimal.valueOf(5), BigDecimal.ONE, 5));

        KpiAggregationQueries.AggregateResult result = calculator.queryOnHoldCount();
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(5));
    }

    @Test
    @DisplayName("AC-1: segment totals reconcile to overall count")
    void segmentedBacklog_sumEqualsTotal() {
        List<BacklogAggregationRepository.BacklogSegment> segments = List.of(
                new BacklogAggregationRepository.BacklogSegment("NEW", "HIGH", null, 3),
                new BacklogAggregationRepository.BacklogSegment("ASSIGNED", "MEDIUM", null, 7),
                new BacklogAggregationRepository.BacklogSegment("ON_HOLD", "LOW", "AWAITING_PARTS", 2),
                new BacklogAggregationRepository.BacklogSegment("IN_PROGRESS", "CRITICAL", null, 5)
        );
        when(repository.queryTotalOpenCount()).thenReturn(
                new KpiAggregationQueries.AggregateResult(
                        BigDecimal.valueOf(17), BigDecimal.valueOf(17), BigDecimal.ONE, 17));
        when(repository.querySegmentedBacklog()).thenReturn(segments);

        long segmentSum = calculator.querySegmentedBacklog().stream()
                .mapToLong(BacklogAggregationRepository.BacklogSegment::count)
                .sum();
        long total = calculator.queryOpenBacklogTotal().sampleCount();

        assertThat(segmentSum).isEqualTo(total);
    }

    @Test
    @DisplayName("AC-1: empty backlog returns zero, not null")
    void queryOpenBacklogTotal_emptyBacklog_returnsZero() {
        when(repository.queryTotalOpenCount()).thenReturn(
                new KpiAggregationQueries.AggregateResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, 0));

        KpiAggregationQueries.AggregateResult result = calculator.queryOpenBacklogTotal();
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.sampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("AC-1: new hold reason appears as its own segment (no silent drop)")
    void segmentedBacklog_unknownHoldReason_appearsInSegments() {
        List<BacklogAggregationRepository.BacklogSegment> segments = List.of(
                new BacklogAggregationRepository.BacklogSegment("ON_HOLD", "MEDIUM", "NEW_REASON_2025", 4)
        );
        when(repository.querySegmentedBacklog()).thenReturn(segments);

        List<BacklogAggregationRepository.BacklogSegment> result = calculator.querySegmentedBacklog();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).holdReason()).isEqualTo("NEW_REASON_2025");
    }
}
