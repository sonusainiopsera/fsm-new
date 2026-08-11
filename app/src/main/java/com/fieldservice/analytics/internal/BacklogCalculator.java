package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Computes backlog KPI values from the {@link BacklogAggregationRepository} (WO-165).
 *
 * <h3>Open-state derivation</h3>
 * The set of open states is derived from {@link BacklogAggregationRepository#OPEN_STATES},
 * which is the complement of the terminal-state set. A new non-terminal lifecycle state
 * automatically enters the backlog count without a code change here.
 *
 * <h3>Segmented backlog</h3>
 * Each distinct (state, priority, hold_reason) combination appears as a separate segment.
 * The totals across all segments equal the overall open count (reconciliation invariant).
 */
@Component
class BacklogCalculator {

    private static final Logger log = LoggerFactory.getLogger(BacklogCalculator.class);

    private final BacklogAggregationRepository repository;

    BacklogCalculator(BacklogAggregationRepository repository) {
        this.repository = repository;
    }

    /**
     * Total count of open work orders (all open states, all priorities).
     */
    @Nullable
    KpiAggregationQueries.AggregateResult queryOpenBacklogTotal() {
        return repository.queryTotalOpenCount();
    }

    /**
     * Count of ON_HOLD work orders (subset of total open backlog).
     */
    KpiAggregationQueries.AggregateResult queryOnHoldCount() {
        return repository.queryOnHoldCount();
    }

    /**
     * Returns backlog counts segmented by state, priority and hold reason.
     *
     * <p>The sum of all segment counts equals the total from {@link #queryOpenBacklogTotal()}.
     * Unknown or new lifecycle states appear as their own segment — nothing is silently dropped
     * into an "other" bucket.
     */
    List<BacklogAggregationRepository.BacklogSegment> querySegmentedBacklog() {
        List<BacklogAggregationRepository.BacklogSegment> segments = repository.querySegmentedBacklog();
        log.debug("backlog.segmented: segments={} totalStates={}",
                segments.size(), segments.stream().map(BacklogAggregationRepository.BacklogSegment::state).distinct().count());
        return segments;
    }

    /**
     * Produces a single {@link KpiAggregationQueries.AggregateResult} for the given segment key,
     * used when persisting an individual segment projection.
     */
    @Nullable
    KpiAggregationQueries.AggregateResult querySegmentCount(String state, @Nullable String priority,
                                                            @Nullable String holdReason) {
        List<BacklogAggregationRepository.BacklogSegment> all = repository.querySegmentedBacklog();
        long count = all.stream()
                .filter(s -> state.equals(s.state())
                        && (priority == null || priority.equals(s.priority()))
                        && (holdReason == null ? s.holdReason() == null : holdReason.equals(s.holdReason())))
                .mapToLong(BacklogAggregationRepository.BacklogSegment::count)
                .sum();
        return new KpiAggregationQueries.AggregateResult(
                BigDecimal.valueOf(count), BigDecimal.valueOf(count), BigDecimal.ONE, (int) count);
    }
}
