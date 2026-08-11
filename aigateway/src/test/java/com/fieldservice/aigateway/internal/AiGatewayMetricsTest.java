package com.fieldservice.aigateway.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiGatewayMetrics} — AC-9.
 * No Spring context required.
 */
@DisplayName("AiGatewayMetrics (AC-9)")
class AiGatewayMetricsTest {

    @Test
    @DisplayName("success records call counter, timer, token counters and cost counter")
    void success_records_all_meters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry, "fake-provider", 0.00003);

        metrics.recordSuccess("completion", 250L, 10, 20);

        assertThat(registry.find("ai.gateway.calls")
                .tag("outcome", "success").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
        assertThat(registry.find("ai.gateway.calls")
                .tag("provider", "fake-provider").counter()).isNotNull();
        assertThat(registry.find("ai.gateway.latency").timer()).isNotNull();
        assertThat(registry.find("ai.gateway.tokens")
                .tag("type", "prompt").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(10.0));
        assertThat(registry.find("ai.gateway.tokens")
                .tag("type", "completion").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(20.0));
        assertThat(registry.find("ai.gateway.estimated.cost").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isGreaterThan(0));
    }

    @Test
    @DisplayName("cost is proportional to token count and unit price")
    void cost_proportional_to_tokens_and_price() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        double costPerThousand = 0.01; // 1 cent per 1000 tokens
        var metrics = new AiGatewayMetrics(registry, "fake", costPerThousand);

        metrics.recordSuccess("completion", 100L, 0, 1000);

        // 1000 tokens × (0.01 / 1000) = 0.01 USD
        double cost = registry.find("ai.gateway.estimated.cost").counter().count();
        assertThat(cost).isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("failure records failure counter")
    void failure_records_failure_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry, "fake", 0.00003);

        metrics.recordFailure("caption", 10_500L, "timeout");

        assertThat(registry.find("ai.gateway.calls")
                .tag("outcome", "failure_timeout").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
    }

    @Test
    @DisplayName("rejected records rejection counter with reason tag")
    void rejected_records_reason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry, "fake", 0.00003);

        metrics.recordRejected("completion", "circuit_open");

        assertThat(registry.find("ai.gateway.calls")
                .tag("outcome", "rejected_circuit_open").counter()).isNotNull()
                .satisfies(c -> assertThat(c.count()).isEqualTo(1.0));
    }
}
