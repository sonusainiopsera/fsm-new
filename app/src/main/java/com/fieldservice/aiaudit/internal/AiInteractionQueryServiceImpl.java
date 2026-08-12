package com.fieldservice.aiaudit.internal;

import com.fieldservice.aiaudit.api.AiInteractionQueryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class AiInteractionQueryServiceImpl implements AiInteractionQueryService {

    private static final int DEFAULT_ROLLING_DAYS = 30;

    private final AiInteractionRepository       interactionRepository;
    private final AiInteractionRatingRepository ratingRepository;

    // Micrometer gauge state — updated on every metrics call
    private final AtomicReference<Double> gaugeHelpfulPct  = new AtomicReference<>(null);
    private final AtomicReference<Double> gaugeLatencyP95  = new AtomicReference<>(null);

    AiInteractionQueryServiceImpl(AiInteractionRepository interactionRepository,
                                   AiInteractionRatingRepository ratingRepository,
                                   MeterRegistry meterRegistry) {
        this.interactionRepository = interactionRepository;
        this.ratingRepository      = ratingRepository;

        Gauge.builder("ai.interaction.helpful_pct",   gaugeHelpfulPct, r -> r.get() != null ? r.get() : 0.0)
                .description("Helpful percentage of rated AI interactions (rolling 30d)")
                .register(meterRegistry);
        Gauge.builder("ai.interaction.latency_p95_ms", gaugeLatencyP95, r -> r.get() != null ? r.get() : 0.0)
                .description("P95 latency in milliseconds of AI interactions (rolling 30d)")
                .register(meterRegistry);
    }

    @Override
    public MetricsSnapshot computeMetrics(Instant from, Instant to) {
        long total = interactionRepository.countByWindow(from, to);

        List<AiInteraction> interactions = interactionRepository.findByWindow(from, to);

        // Outcome distribution
        Map<String, Long> dist = interactions.stream()
                .collect(Collectors.groupingBy(AiInteraction::getOutcome, Collectors.counting()));

        // Helpful percentage — only rated interactions count
        List<AiInteractionRating> ratings = ratingRepository.findAll().stream()
                .filter(r -> interactions.stream()
                        .anyMatch(i -> i.getId().equals(r.getAiInteractionId())))
                .toList();

        Double helpfulPct = null;
        if (!ratings.isEmpty()) {
            long helpfulCount = ratings.stream()
                    .filter(r -> "HELPFUL".equals(r.getRating()))
                    .count();
            helpfulPct = (double) helpfulCount / ratings.size() * 100.0;
        }

        // P95 latency
        Double p95 = interactionRepository.computeP95LatencyMs(from, to);

        // Estimated cost per interaction
        Double costPerInteraction = null;
        if (total > 0) {
            double totalCost = interactions.stream()
                    .filter(i -> i.getEstimatedCost() != null)
                    .mapToDouble(i -> i.getEstimatedCost().doubleValue())
                    .sum();
            if (totalCost > 0) {
                costPerInteraction = totalCost / total;
            }
        }

        // Update Micrometer gauges for the default rolling window
        Instant rollingFrom = Instant.now().minus(DEFAULT_ROLLING_DAYS, ChronoUnit.DAYS);
        if (from.equals(rollingFrom) || from.isBefore(rollingFrom)) {
            gaugeHelpfulPct.set(helpfulPct);
            gaugeLatencyP95.set(p95);
        }

        return new MetricsSnapshot(total, helpfulPct, dist, p95, costPerInteraction);
    }
}
