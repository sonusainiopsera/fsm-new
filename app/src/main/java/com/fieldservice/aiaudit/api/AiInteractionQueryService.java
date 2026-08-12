package com.fieldservice.aiaudit.api;

import java.time.Instant;
import java.util.Map;

/**
 * Query surface for AI interaction aggregate metrics (Phase 4 exit gate measurements).
 *
 * <p>Restricted to MANAGER and ADMIN roles — callers must enforce role predicate before
 * invoking this service.
 */
public interface AiInteractionQueryService {

    /**
     * Aggregate metrics over the given window.
     *
     * @param from  start of window (inclusive)
     * @param to    end of window (exclusive)
     * @return aggregate metrics snapshot
     */
    MetricsSummary metrics(Instant from, Instant to);

    /**
     * Snapshot of Phase 4 exit gate metrics.
     *
     * @param totalInteractions    total AI interactions in the window
     * @param helpfulPercentage    percentage of rated interactions marked HELPFUL, or null if none rated
     * @param outcomeDistribution  counts by outcome code
     * @param latencyP95Ms         p95 latency in milliseconds, or null if no latency data
     * @param estimatedCostPerInteraction average estimated cost per interaction (USD)
     */
    record MetricsSummary(
            long totalInteractions,
            Double helpfulPercentage,
            Map<String, Long> outcomeDistribution,
            Long latencyP95Ms,
            java.math.BigDecimal estimatedCostPerInteraction
    ) {}
}
