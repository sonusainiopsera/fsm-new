package com.fieldservice.aigateway;

import com.fieldservice.aigateway.internal.AiGatewayProperties;
import com.fieldservice.aigateway.internal.EgressAllowList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for EgressAllowList — no Spring context required. */
class EgressAllowListTest {

    private EgressAllowList allowListWith(List<String> hosts) {
        var props = new AiGatewayProperties(
                new AiGatewayProperties.Copilot(true, 50),
                new AiGatewayProperties.Provider(
                        "https://api.example.ai", "", hosts,
                        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(10)),
                new AiGatewayProperties.Resilience(
                        new AiGatewayProperties.Resilience.CircuitBreakerConfig(50f, 20, java.time.Duration.ofSeconds(30), 3),
                        new AiGatewayProperties.Resilience.BulkheadConfig(16),
                        new AiGatewayProperties.Resilience.TimeLimiterConfig(java.time.Duration.ofSeconds(10))),
                new AiGatewayProperties.Metrics(0.002));
        return new EgressAllowList(props);
    }

    @Test
    @DisplayName("permitted host passes allow-list check")
    void permittedHost_passes() {
        var allowList = allowListWith(List.of("api.example.ai"));
        assertThatCode(() -> allowList.assertAllowed("https://api.example.ai/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-allow-listed host is refused and logged as security event")
    void nonAllowlistedHost_isRefused() {
        var allowList = allowListWith(List.of("api.example.ai"));
        assertThatThrownBy(() -> allowList.assertAllowed("https://attacker.evil.com/exfiltrate"))
                .isInstanceOf(EgressAllowList.EgressBlockedException.class);
    }

    @Test
    @DisplayName("empty allow-list refuses all calls")
    void emptyAllowList_refusesAll() {
        var allowList = allowListWith(List.of());
        assertThatThrownBy(() -> allowList.assertAllowed("https://api.example.ai"))
                .isInstanceOf(EgressAllowList.EgressBlockedException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest(name = "case-insensitive match: {0}")
    @ValueSource(strings = {"API.EXAMPLE.AI", "Api.Example.Ai", "api.EXAMPLE.ai"})
    @DisplayName("host matching is case-insensitive")
    void caseInsensitiveMatching(String mixedCaseHost) {
        var allowList = allowListWith(List.of("api.example.ai"));
        assertThatCode(() -> allowList.assertAllowed("https://" + mixedCaseHost + "/v1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("malformed URL is refused")
    void malformedUrl_isRefused() {
        var allowList = allowListWith(List.of("api.example.ai"));
        assertThatThrownBy(() -> allowList.assertAllowed("not-a-url"))
                .isInstanceOf(EgressAllowList.EgressBlockedException.class);
    }
}
