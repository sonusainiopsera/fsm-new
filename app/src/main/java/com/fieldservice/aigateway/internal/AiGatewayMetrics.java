package com.fieldservice.aigateway.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the AI gateway.
 *
 * <p>Meters registered:
 * <ul>
 *   <li>{@code ai_gateway_calls_total} — tagged by provider, operation, outcome</li>
 *   <li>{@code ai_gateway_latency_seconds} — histogram per operation/outcome</li>
 *   <li>{@code ai_gateway_tokens_total} — tagged by provider, operation, direction</li>
 *   <li>{@code ai_gateway_estimated_cost_total} — USD estimate per call</li>
 * </ul>
 */
@Component
class AiGatewayMetrics {

    static final String PROVIDER_TAG  = "provider";
    static final String OPERATION_TAG = "operation";
    static final String OUTCOME_TAG   = "outcome";

    private final MeterRegistry registry;
    private final double costPer1kTokens;

    AiGatewayMetrics(MeterRegistry registry, AiGatewayProperties properties) {
        this.registry = registry;
        this.costPer1kTokens = properties.metrics().costPer1kTokens();
    }

    void recordSuccess(String provider, String operation, long latencyMs, int promptTokens, int completionTokens) {
        incrementCall(provider, operation, "success");
        recordLatency(provider, operation, "success", latencyMs);
        recordTokens(provider, operation, promptTokens, completionTokens);
        recordCost(provider, operation, promptTokens + completionTokens);
    }

    void recordFailure(String provider, String operation, long latencyMs, String reason) {
        incrementCall(provider, operation, reason);
        recordLatency(provider, operation, reason, latencyMs);
    }

    private void incrementCall(String provider, String operation, String outcome) {
        Counter.builder("ai_gateway_calls_total")
                .tag(PROVIDER_TAG, provider)
                .tag(OPERATION_TAG, operation)
                .tag(OUTCOME_TAG, outcome)
                .register(registry)
                .increment();
    }

    private void recordLatency(String provider, String operation, String outcome, long latencyMs) {
        Timer.builder("ai_gateway_latency_seconds")
                .tag(PROVIDER_TAG, provider)
                .tag(OPERATION_TAG, operation)
                .tag(OUTCOME_TAG, outcome)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    private void recordTokens(String provider, String operation, int promptTokens, int completionTokens) {
        Counter.builder("ai_gateway_tokens_total")
                .tag(PROVIDER_TAG, provider)
                .tag(OPERATION_TAG, operation)
                .tag("direction", "prompt")
                .register(registry)
                .increment(promptTokens);
        Counter.builder("ai_gateway_tokens_total")
                .tag(PROVIDER_TAG, provider)
                .tag(OPERATION_TAG, operation)
                .tag("direction", "completion")
                .register(registry)
                .increment(completionTokens);
    }

    private void recordCost(String provider, String operation, int totalTokens) {
        double cost = (totalTokens / 1000.0) * costPer1kTokens;
        Counter.builder("ai_gateway_estimated_cost_total")
                .tag(PROVIDER_TAG, provider)
                .tag(OPERATION_TAG, operation)
                .register(registry)
                .increment(cost);
    }
}
