package com.fieldservice.analytics.backlog;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.backlog.BacklogCalculator;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BacklogCalculator.
 * No Spring context — dependencies injected directly; JdbcTemplate is mocked.
 */
@ExtendWith(MockitoExtension.class)
class BacklogCalculatorTest {

    static final Instant NOW = Instant.parse("2025-06-01T08:00:00Z");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    JdbcTemplate analyticsJdbc;

    BacklogCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new BacklogCalculator(analyticsJdbc, clock);
    }

    @Test
    @DisplayName("metricKey returns the correct stable key")
    void metricKey_correct() {
        assertThat(calculator.metricKey()).isEqualTo("backlog.open.count");
    }

    @Test
    @DisplayName("ALL segment equals sum of STATE segments — reconciliation passes")
    void allSegment_equalsSum_ofStateSegments() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("state", "NEW",         "priority", "HIGH",   "cnt", 5L),
                Map.of("state", "ASSIGNED",    "priority", "MEDIUM", "cnt", 3L),
                Map.of("state", "IN_PROGRESS", "priority", "HIGH",   "cnt", 2L)
        ));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        long all = valueFor(results, "ALL");
        long stateSum = results.stream()
                .filter(r -> r.segmentKey().startsWith("STATE:"))
                .mapToLong(r -> r.value().longValue())
                .sum();

        assertThat(all).isEqualTo(10L);
        assertThat(all).isEqualTo(stateSum);
    }

    @Test
    @DisplayName("STATE segments are produced for each state in query results")
    void stateSegments_presentForEachState() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("state", "NEW",      "priority", "LOW",    "cnt", 4L),
                Map.of("state", "ON_HOLD",  "priority", "HIGH",   "cnt", 2L),
                Map.of("state", "ASSIGNED", "priority", "MEDIUM", "cnt", 6L)
        ));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        Set<String> segments = results.stream().map(KpiAggregator.KpiAggregatorResult::segmentKey)
                .collect(Collectors.toSet());

        assertThat(segments).contains("STATE:NEW", "STATE:ON_HOLD", "STATE:ASSIGNED");
    }

    @Test
    @DisplayName("PRIORITY segments are produced for each priority in query results")
    void prioritySegments_presentForEachPriority() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("state", "NEW",  "priority", "HIGH",     "cnt", 3L),
                Map.of("state", "NEW",  "priority", "LOW",      "cnt", 2L),
                Map.of("state", "NEW",  "priority", "CRITICAL", "cnt", 1L)
        ));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        Set<String> segments = results.stream().map(KpiAggregator.KpiAggregatorResult::segmentKey)
                .collect(Collectors.toSet());

        assertThat(segments).contains("PRIORITY:HIGH", "PRIORITY:LOW", "PRIORITY:CRITICAL");
    }

    @Test
    @DisplayName("Empty backlog produces ALL=0 and no state/priority segments")
    void emptyBacklog_producesZeroAllNoSubSegments() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        long all = valueFor(results, "ALL");
        assertThat(all).isZero();

        long stateOrPrioritySegments = results.stream()
                .filter(r -> r.segmentKey().startsWith("STATE:") || r.segmentKey().startsWith("PRIORITY:"))
                .count();
        assertThat(stateOrPrioritySegments).isZero();
    }

    @Test
    @DisplayName("Priority segments aggregate correctly across multiple states")
    void prioritySegments_aggregateAcrossStates() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("state", "NEW",      "priority", "HIGH", "cnt", 4L),
                Map.of("state", "ASSIGNED", "priority", "HIGH", "cnt", 6L)
        ));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        long highPriorityTotal = valueFor(results, "PRIORITY:HIGH");
        assertThat(highPriorityTotal).isEqualTo(10L);
    }

    @Test
    @DisplayName("workKey is CURRENT for all results")
    void windowKey_isCurrent() {
        when(analyticsJdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("state", "NEW", "priority", "HIGH", "cnt", 1L)
        ));

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        assertThat(results).allMatch(r -> "CURRENT".equals(r.windowKey()));
    }

    @Test
    @DisplayName("openStates published vocabulary contains exactly the expected open states")
    void openStates_vocabularyContainsExpectedStates() {
        Set<WorkOrderStatus> open = WorkOrderStatus.openStates();
        assertThat(open).containsExactlyInAnyOrder(
                WorkOrderStatus.NEW,
                WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.EN_ROUTE,
                WorkOrderStatus.IN_PROGRESS,
                WorkOrderStatus.ON_HOLD
        );
        // Terminal states must NOT be included
        assertThat(open).doesNotContain(
                WorkOrderStatus.COMPLETED,
                WorkOrderStatus.CLOSED,
                WorkOrderStatus.CANCELLED
        );
    }

    private static long valueFor(List<KpiAggregator.KpiAggregatorResult> results, String segment) {
        return results.stream()
                .filter(r -> segment.equals(r.segmentKey()))
                .map(KpiAggregator.KpiAggregatorResult::value)
                .filter(v -> v != null)
                .mapToLong(BigDecimal::longValue)
                .findFirst()
                .orElse(0L);
    }
}
