package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.platform.api.AiUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed integration tests for {@link HttpAiProviderAdapter} — AC-2, AC-3, AC-13.
 *
 * <p>All failure modes are exercised without any Spring context or real provider account.
 */
@DisplayName("HttpAiProviderAdapter WireMock integration tests (AC-2, AC-3, AC-13)")
class HttpAiProviderAdapterWireMockTest {

    private WireMockServer wireMock;
    private HttpAiProviderAdapter adapter;
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        String endpoint = "http://localhost:" + wireMock.port() + "/v1/chat/completions";

        AiGatewayProperties props = buildProps(endpoint, 10_000L);
        AiGatewayResilienceConfig resilience = new AiGatewayResilienceConfig(props);
        // Test-only allow-list: permit localhost without DNS verification (WireMock runs on loopback)
        EgressAllowList allowList = new EgressAllowList(List.of("localhost")) {
            @Override
            void validate(java.net.URI uri) { /* permitted for test — WireMock runs on loopback */ }
        };
        SecretsProvider secrets = new EnvironmentSecretsProvider("AI_PROVIDER_API_KEY") {
            @Override
            public String getApiKey() { return "test-key-not-real"; }
        };
        AiGatewayMetrics metrics = new AiGatewayMetrics(
                new SimpleMeterRegistry(), "wiremock-provider", 0.00003);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(10000);
        RestClient restClient = RestClient.builder().requestFactory(factory).build();

        adapter = new HttpAiProviderAdapter(
                restClient, allowList, secrets, resilience, metrics, props,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // ── AC-13: success ────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-13: provider success returns AiCompletionResponse")
    void success_returns_response() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"id":"chatcmpl-1","model":"gpt-4o",
                             "choices":[{"message":{"role":"assistant","content":"Check the gauge."},"finish_reason":"stop"}],
                             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                            """)));

        AiCompletionResponse resp = adapter.complete(
                new AiCompletionRequest(USER, "sys", "What to check?", 0));

        assertThat(resp.content()).isEqualTo("Check the gauge.");
        assertThat(resp.promptTokens()).isEqualTo(10);
        assertThat(resp.completionTokens()).isEqualTo(5);
    }

    // ── AC-5: 500 response → safe 503 envelope ────────────────────────────────

    @Test
    @DisplayName("AC-5, AC-13: provider HTTP 500 throws AiUnavailableException with safe message")
    void provider_500_throws_unavailable() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":{\"message\":\"Internal server error\"}}")));

        assertThatThrownBy(() -> adapter.complete(
                new AiCompletionRequest(USER, null, "test", 0)))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(ex -> {
                    // Message must be safe — no internal detail
                    assertThat(ex.getMessage())
                            .doesNotContain("Internal server error")
                            .doesNotContain("500");
                });
    }

    // ── AC-13: malformed JSON body ────────────────────────────────────────────

    @Test
    @DisplayName("AC-13: malformed JSON body is treated as failure, not empty answer")
    void malformed_json_throws_unavailable() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{not valid json{{{")));

        assertThatThrownBy(() -> adapter.complete(
                new AiCompletionRequest(USER, null, "test", 0)))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ── AC-2: 11-second delay → timeout ──────────────────────────────────────

    @Test
    @DisplayName("AC-2: provider delay exceeding 10s time limit throws AiUnavailableException")
    void slow_provider_exceeding_budget_throws_timeout() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(11_000) // 11 seconds — exceeds 10s budget
                        .withBody("{}")));

        // Override with 1s time limit for test speed
        String endpoint = "http://localhost:" + wireMock.port() + "/v1/chat/completions";
        AiGatewayProperties props = buildProps(endpoint, 1_000L); // 1s for test
        AiGatewayResilienceConfig resilience = new AiGatewayResilienceConfig(props);
        EgressAllowList allowList = new EgressAllowList(List.of("localhost")) {
            @Override void validate(java.net.URI uri) {}
        };
        SecretsProvider secrets = new EnvironmentSecretsProvider("AI_PROVIDER_API_KEY") {
            @Override public String getApiKey() { return "test-key"; }
        };
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500); // read timeout > time limit
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        HttpAiProviderAdapter slowAdapter = new HttpAiProviderAdapter(
                restClient, allowList, secrets, resilience,
                new AiGatewayMetrics(new SimpleMeterRegistry(), "test", 0.0001), props,
                Executors.newVirtualThreadPerTaskExecutor());

        assertThatThrownBy(() -> slowAdapter.complete(
                new AiCompletionRequest(USER, null, "test", 0)))
                .isInstanceOf(AiUnavailableException.class);
    }

    // ── AC-5: response body contains no provider detail ───────────────────────

    @Test
    @DisplayName("AC-5: 429 response does not expose provider error text")
    void provider_429_throws_safe_unavailable() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"error\":{\"message\":\"Rate limit exceeded\",\"code\":\"rate_limit_exceeded\"}}")));

        assertThatThrownBy(() -> adapter.complete(
                new AiCompletionRequest(USER, null, "test", 0)))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("rate_limit_exceeded"));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private AiGatewayProperties buildProps(String endpoint, long timeLimitMs) {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProvider().setEndpoint(endpoint);
        props.getProvider().setAllowedHosts(List.of("localhost"));
        props.getProvider().setConnectTimeout(Duration.ofMillis(500));
        props.getProvider().setReadTimeout(Duration.ofMillis(timeLimitMs + 500));
        props.getResilience().setTimeLimitDuration(Duration.ofMillis(timeLimitMs));
        props.getResilience().setSlidingWindowSize(5);
        return props;
    }
}
