package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.FakeAiGatewayAdapter;
import com.fieldservice.aigateway.api.AiCapExceededException;
import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for package-private AI gateway internals — no Spring context, no network.
 */
@DisplayName("AI gateway internal unit tests")
class AiGatewayInternalUnitTest {

    // ── EgressAllowList ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty allow-list refuses all calls (never fail-open)")
    void egressAllowList_emptyListRefusesAll() {
        var list = new EgressAllowList(List.of());
        assertThatThrownBy(() -> list.validate("https://api.openai.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Host not in allow-list is refused with security log")
    void egressAllowList_unlistedHostRefused() {
        var list = new EgressAllowList(List.of("api.trusted.com"));
        assertThatThrownBy(() -> list.validate("https://api.other.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not in the egress allow-list");
    }

    @Test
    @DisplayName("Private IPv4 literal in allow-list refused (SSRF DNS-rebinding)")
    void egressAllowList_privateIpv4Refused() {
        var list = new EgressAllowList(List.of("192.168.1.1"));
        assertThatThrownBy(() -> list.validate("https://192.168.1.1/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Loopback address refused even when in allow-list")
    void egressAllowList_loopbackRefused() {
        var list = new EgressAllowList(List.of("127.0.0.1"));
        assertThatThrownBy(() -> list.validate("https://127.0.0.1/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Null allow-list treated as empty — refuses all")
    void egressAllowList_nullListRefusesAll() {
        var list = new EgressAllowList(null);
        assertThatThrownBy(() -> list.validate("https://api.example.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Blank entries in allow-list are filtered out")
    void egressAllowList_blankEntriesFiltered() {
        var list = new EgressAllowList(List.of("", "  "));
        assertThatThrownBy(() -> list.validate("https://api.example.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("empty");
    }

    // ── FeatureFlagGuardAdapter ─────────────────────────────────────────────

    @Test
    @DisplayName("Flag disabled → AiUnavailableException immediately, no delegate call")
    void flagDisabled_throwsUnavailableWithoutCallingDelegate() {
        var guard = new FeatureFlagGuardAdapter(false, null, null);
        assertThatThrownBy(() -> guard.complete(new AiCompletionRequest("u1", "op", "hello", null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    @DisplayName("Flag enabled → delegates to inner adapter successfully")
    void flagEnabled_delegatesToFakeAdapter() {
        var fake = new FakeAiGatewayAdapter();
        var guard = new FeatureFlagGuardAdapter(true, fake, null);
        var result = guard.complete(new AiCompletionRequest("u1", "op", "hello", null));
        assertThat(result.content()).isEqualTo(FakeAiGatewayAdapter.FAKE_COMPLETION);
        assertThat(result.complete()).isTrue();
    }

    @Test
    @DisplayName("Flag enabled for caption → delegates to fake adapter")
    void flagEnabled_captionDelegatesToFake() {
        var fake = new FakeAiGatewayAdapter();
        var guard = new FeatureFlagGuardAdapter(true, fake, null);
        var result = guard.caption(
                new com.fieldservice.aigateway.api.AiVisionRequest("u1", "op", "s3://bucket/img.jpg", null));
        assertThat(result.caption()).isEqualTo(FakeAiGatewayAdapter.FAKE_CAPTION);
    }

    // ── AiGatewayMetrics ────────────────────────────────────────────────────

    @Test
    @DisplayName("recordCall increments ai_gateway_calls_total counter")
    void metrics_callsTotalCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry);

        metrics.recordCall("http", "complete", "success",
                java.time.Duration.ofMillis(200), 30, 30 * 0.00002);

        Counter calls = registry.find(AiGatewayMetrics.CALLS_TOTAL)
                .tag("provider", "http")
                .tag("operation", "complete")
                .tag("outcome", "success")
                .counter();
        assertThat(calls).isNotNull();
        assertThat(calls.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordCall increments ai_gateway_tokens_total by token count")
    void metrics_tokensTotalCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry);

        metrics.recordCall("http", "complete", "success",
                java.time.Duration.ofMillis(100), 45, 45 * 0.00002);

        Counter tokens = registry.find(AiGatewayMetrics.TOKENS_TOTAL)
                .tag("provider", "http")
                .tag("operation", "complete")
                .counter();
        assertThat(tokens).isNotNull();
        assertThat(tokens.count()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("Multiple outcomes are tracked with separate counter tags")
    void metrics_differentOutcomesTrackedSeparately() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiGatewayMetrics(registry);

        metrics.recordCall("http", "complete", "success",
                java.time.Duration.ofMillis(100), 10, 0.0002);
        metrics.recordCall("http", "complete", "timeout",
                java.time.Duration.ofMillis(10000), 0, 0.0);
        metrics.recordCall("http", "complete", "circuit-open",
                java.time.Duration.ofMillis(1), 0, 0.0);

        double successCount = registry.find(AiGatewayMetrics.CALLS_TOTAL)
                .tag("outcome", "success").counter().count();
        double timeoutCount = registry.find(AiGatewayMetrics.CALLS_TOTAL)
                .tag("outcome", "timeout").counter().count();
        double circuitCount = registry.find(AiGatewayMetrics.CALLS_TOTAL)
                .tag("outcome", "circuit-open").counter().count();

        assertThat(successCount).isEqualTo(1.0);
        assertThat(timeoutCount).isEqualTo(1.0);
        assertThat(circuitCount).isEqualTo(1.0);
    }

    // ── RedisUsageCapService helpers ─────────────────────────────────────────

    @Test
    @DisplayName("secondsUntilMidnightUtc returns a positive value less than 86400")
    void capService_secondsUntilMidnight_inRange() {
        long seconds = RedisUsageCapService.secondsUntilMidnightUtc();
        assertThat(seconds).isGreaterThan(0).isLessThanOrEqualTo(86400);
    }

    // ── AiCapExceededException ───────────────────────────────────────────────

    @Test
    @DisplayName("AiCapExceededException exposes retryAfterSeconds")
    void capException_retryAfterSecondsAccessible() {
        var ex = new AiCapExceededException("user1", 3600L);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(3600L);
    }
}
