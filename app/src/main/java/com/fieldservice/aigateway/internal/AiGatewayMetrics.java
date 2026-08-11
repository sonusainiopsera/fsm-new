package com.fieldservice.aigateway.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metric registration for the AI gateway.
 *
 * <p>Metrics published:
 * <ul>
 *   <li>{@code ai_gateway_calls_total} — counter tagged by provider, operation, outcome</li>
 *   <li>{@code ai_gateway_latency_seconds} — histogram of end-to-end call latency</li>
 *   <li>{@code ai_gateway_tokens_total} — cumulative token consumption</li>
 *   <li>{@code ai_gateway_estimated_cost_total} — estimated cost in USD at configured unit price</li>
 * </ul>
 */
@Component
public class AiGatewayMetrics {

    static final String CALLS_TOTAL = "ai_gateway_calls_total";
    static final String LATENCY_SECONDS = "ai_gateway_latency_seconds";
    static final String TOKENS_TOTAL = "ai_gateway_tokens_total";
    static final String ESTIMATED_COST_TOTAL = "ai_gateway_estimated_cost_total";

    private final MeterRegistry registry;

    public AiGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCall(String provider, String operation, String outcome, Duration latency,
                    int totalTokens, double estimatedCost) {
        Counter.builder(CALLS_TOTAL)
                .tag("provider", provider)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();

        Timer.builder(LATENCY_SECONDS)
                .tag("provider", provider)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .record(latency);

        Counter.builder(TOKENS_TOTAL)
                .tag("provider", provider)
                .tag("operation", operation)
                .register(registry)
                .increment(totalTokens);

        Counter.builder(ESTIMATED_COST_TOTAL)
                .tag("provider", provider)
                .tag("operation", operation)
                .register(registry)
                .increment(estimatedCost);
    }
}
