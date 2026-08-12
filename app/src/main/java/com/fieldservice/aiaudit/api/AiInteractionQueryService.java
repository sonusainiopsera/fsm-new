package com.fieldservice.aiaudit.api;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only query surface for AI interaction metrics.
 *
 * <p>Restricted to MANAGER and ADMIN roles — callers are responsible for
 * enforcing role access via {@code @PreAuthorize} on the controller layer.
 */
public interface AiInteractionQueryService {

    /**
     * Computes aggregate metrics for the Phase 4 exit gates over the specified window.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (exclusive)
     * @return metrics snapshot for the window
     */
    MetricsSnapshot computeMetrics(Instant from, Instant to);

    /**
     * Metrics snapshot for the AI interaction Phase 4 exit gates.
     *
     * @param interactions              total interaction count
     * @param helpfulPercentage         percent of rated interactions rated HELPFUL (0–100);
     *                                  null when no rated interactions exist
     * @param outcomeDistribution       count by outcome name
     * @param latencyP95Ms              p95 latency in milliseconds; null when no data
     * @param estimatedCostPerInteraction average estimated cost; null when no data
     */
    record MetricsSnapshot(
            long               interactions,
            Double             helpfulPercentage,
            Map<String, Long>  outcomeDistribution,
            Double             latencyP95Ms,
            Double             estimatedCostPerInteraction
    ) {}
}
