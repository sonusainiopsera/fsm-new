package com.fieldservice.aigateway.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the AI gateway.
 *
 * <p>Published meters:
 * <ul>
 *   <li>{@code ai.gateway.calls} (counter) — tagged provider, operation, outcome</li>
 *   <li>{@code ai.gateway.latency} (timer) — tagged provider, operation</li>
 *   <li>{@code ai.gateway.tokens} (counter) — tagged provider, operation, type (prompt|completion)</li>
 *   <li>{@code ai.gateway.estimated.cost} (counter) — tagged provider, operation; unit USD</li>
 * </ul>
 */
class AiGatewayMetrics {

    private static final String PROVIDER_TAG = "provider";
    private static final String OPERATION_TAG = "operation";
    private static final String OUTCOME_TAG = "outcome";
    private static final String TOKEN_TYPE_TAG = "type";

    private final MeterRegistry registry;
    private final String providerName;
    private final double costPerThousandTokens;

    AiGatewayMetrics(MeterRegistry registry, String providerName, double costPerThousandTokens) {
        this.registry = registry;
        this.providerName = providerName;
        this.costPerThousandTokens = costPerThousandTokens;
    }

    void recordSuccess(String operation, long latencyMs, int promptTokens, int completionTokens) {
        callCounter(operation, "success").increment();
        latencyTimer(operation).record(latencyMs, TimeUnit.MILLISECONDS);
        tokenCounter(operation, "prompt").increment(promptTokens);
        tokenCounter(operation, "completion").increment(completionTokens);
        estimatedCostCounter(operation).increment(
                (promptTokens + completionTokens) * costPerThousandTokens / 1000.0);
    }

    void recordFailure(String operation, long latencyMs, String reason) {
        callCounter(operation, "failure_" + reason).increment();
        latencyTimer(operation).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    void recordRejected(String operation, String reason) {
        callCounter(operation, "rejected_" + reason).increment();
    }

    private Counter callCounter(String operation, String outcome) {
        return Counter.builder("ai.gateway.calls")
                .tag(PROVIDER_TAG, providerName)
                .tag(OPERATION_TAG, operation)
                .tag(OUTCOME_TAG, outcome)
                .register(registry);
    }

    private Timer latencyTimer(String operation) {
        return Timer.builder("ai.gateway.latency")
                .tag(PROVIDER_TAG, providerName)
                .tag(OPERATION_TAG, operation)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    private Counter tokenCounter(String operation, String type) {
        return Counter.builder("ai.gateway.tokens")
                .tag(PROVIDER_TAG, providerName)
                .tag(OPERATION_TAG, operation)
                .tag(TOKEN_TYPE_TAG, type)
                .register(registry);
    }

    private Counter estimatedCostCounter(String operation) {
        return Counter.builder("ai.gateway.estimated.cost")
                .tag(PROVIDER_TAG, providerName)
                .tag(OPERATION_TAG, operation)
                .baseUnit("USD")
                .register(registry);
    }
}
