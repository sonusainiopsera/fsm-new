package com.fieldservice.aiaudit.internal;

import com.fieldservice.aiaudit.api.AiInteractionQueryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes Phase 4 exit gate metrics from the ai_interaction table.
 *
 * <p>Micrometer gauges expose a 90-day rolling window so dashboards and alerts do not
 * query the table directly.
 */
@Service
class AiInteractionQueryServiceImpl implements AiInteractionQueryService {

    private final AiInteractionRepository interactions;
    private final AiInteractionRatingRepository ratings;
    private final MeterRegistry meterRegistry;

    AiInteractionQueryServiceImpl(
            AiInteractionRepository interactions,
            AiInteractionRatingRepository ratings,
            MeterRegistry meterRegistry) {
        this.interactions = interactions;
        this.ratings = ratings;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("ai.interaction.helpful_pct", this, svc -> {
                    Instant to = Instant.now();
                    Instant from = to.minus(90, ChronoUnit.DAYS);
                    MetricsSummary m = svc.metrics(from, to);
                    return m.helpfulPercentage() != null ? m.helpfulPercentage() : Double.NaN;
                })
                .description("Percentage of rated AI interactions marked HELPFUL (90-day rolling window)")
                .register(meterRegistry);

        Gauge.builder("ai.interaction.p95_latency_ms", this, svc -> {
                    Instant to = Instant.now();
                    Instant from = to.minus(90, ChronoUnit.DAYS);
                    MetricsSummary m = svc.metrics(from, to);
                    return m.latencyP95Ms() != null ? (double) m.latencyP95Ms() : Double.NaN;
                })
                .description("p95 AI interaction latency in milliseconds (90-day rolling window)")
                .register(meterRegistry);

        Gauge.builder("ai.interaction.cost_per_interaction", this, svc -> {
                    Instant to = Instant.now();
                    Instant from = to.minus(90, ChronoUnit.DAYS);
                    MetricsSummary m = svc.metrics(from, to);
                    return m.estimatedCostPerInteraction().doubleValue();
                })
                .description("Average estimated cost per AI interaction in USD (90-day rolling window)")
                .register(meterRegistry);
    }

    @Override
    @Transactional(readOnly = true)
    public MetricsSummary metrics(Instant from, Instant to) {
        long total = interactions.countByWindow(from, to);
        Long p95 = interactions.findP95LatencyMs(from, to);
        BigDecimal avgCost = interactions.findAvgEstimatedCost(from, to);
        if (avgCost == null) avgCost = BigDecimal.ZERO;

        Map<String, Long> dist = buildOutcomeDistribution(interactions.findOutcomeDistribution(from, to));

        long ratedCount = ratings.countRatedByWindow(from, to);
        Double helpfulPct = null;
        if (ratedCount > 0) {
            long helpfulCount = ratings.countHelpfulByWindow(from, to);
            helpfulPct = (double) helpfulCount / ratedCount * 100.0;
        }

        return new MetricsSummary(total, helpfulPct, dist, p95, avgCost);
    }

    private static Map<String, Long> buildOutcomeDistribution(List<Object[]> rows) {
        Map<String, Long> dist = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String outcome = (String) row[0];
            long count = ((Number) row[1]).longValue();
            dist.put(outcome, count);
        }
        return dist;
    }
}
