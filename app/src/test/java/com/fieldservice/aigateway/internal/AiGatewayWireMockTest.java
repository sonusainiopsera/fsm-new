package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link HttpAiProviderAdapter} against a WireMock stub provider.
 *
 * <p>Tests each failure mode without a Spring context — the adapter is wired manually
 * to the WireMock base URL.
 */
@WireMockTest
@DisplayName("AI gateway WireMock integration tests")
class AiGatewayWireMockTest {

    private HttpAiProviderAdapter adapter;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void buildAdapter(WireMockRuntimeInfo wm) {
        String baseUrl = wm.getHttpBaseUrl();

        // Use a no-op allow-list for WireMock tests — SSRF protection is tested separately
        EgressAllowList noOpAllowList = new EgressAllowList(List.of("localhost", "127.0.0.1")) {
            @Override
            void validate(String url) {
                // skip IP check for localhost in test
            }
        };

        // Secrets: return a test placeholder key
        SecretsProvider testSecrets = new SecretsProvider() {
            @Override public String getApiKey() { return "test-key"; }
            @Override public void refresh() {}
        };

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(12)); // allow slower WireMock responses in CI

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        CircuitBreaker cb = CircuitBreaker.of("test-cb",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50).slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build());

        Bulkhead bh = Bulkhead.of("test-bh",
                BulkheadConfig.custom().maxConcurrentCalls(8).maxWaitDuration(Duration.ZERO).build());

        // Short timeout for the slow-response test: 2s budget so the test doesn't take 10s
        TimeLimiter tl = TimeLimiter.of("test-tl",
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(2)).build());

        Retry retry = Retry.of("test-retry",
                RetryConfig.custom().maxAttempts(1).build());

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var metrics = new AiGatewayMetrics(meterRegistry);

        adapter = new HttpAiProviderAdapter(restClient, noOpAllowList, testSecrets,
                cb, bh, tl, retry, executor, metrics, baseUrl, 0.00002);
    }

    @Test
    @DisplayName("Provider 200 OK → AiCompletionResponse with content and tokens")
    void success_200_returnsCompletion(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(okJson("""
                        {
                          "id": "cmpl-001",
                          "choices": [{"message":{"role":"assistant","content":"Hello!"},"finishReason":"stop"}],
                          "usage": {"promptTokens":5,"completionTokens":3,"totalTokens":8}
                        }
                        """)));

        var result = adapter.complete(new AiCompletionRequest("u1", "op", "Hi", null));

        assertThat(result.content()).isEqualTo("Hello!");
        assertThat(result.promptTokens()).isEqualTo(5);
        assertThat(result.completionTokens()).isEqualTo(3);
        assertThat(result.complete()).isTrue();
    }

    @Test
    @DisplayName("Provider 500 → AiUnavailableException (never fail-open)")
    void provider500_throwsUnavailable() {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"internal\"}")));

        assertThatThrownBy(() -> adapter.complete(new AiCompletionRequest("u1", "op", "test", null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("internal") // no upstream detail in message
                .hasMessageNotContaining("500");
    }

    @Test
    @DisplayName("Provider 429 → AiUnavailableException (upstream rate-limit treated as degraded)")
    void provider429_throwsUnavailable() {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"rate limit\"}")));

        assertThatThrownBy(() -> adapter.complete(new AiCompletionRequest("u1", "op", "test", null)))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("Provider returns malformed JSON → AiUnavailableException")
    void malformedBody_throwsUnavailable() {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(okJson("{\"not_choices\":[]}")));

        assertThatThrownBy(() -> adapter.complete(new AiCompletionRequest("u1", "op", "test", null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    @DisplayName("Provider responds after 2s TimeLimiter budget → AiUnavailableException")
    void slowResponse_exceedsBudget_throwsUnavailable() {
        // WireMock delays 3 seconds; adapter TimeLimiter is set to 2s in setUp
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"x\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"},\"finishReason\":\"stop\"}],\"usage\":{\"promptTokens\":1,\"completionTokens\":1,\"totalTokens\":2}}")));

        assertThatThrownBy(() -> adapter.complete(new AiCompletionRequest("u1", "op", "test", null)))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("Connection reset → AiUnavailableException")
    void connectionReset_throwsUnavailable() {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(aResponse().withFault(
                        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter.complete(new AiCompletionRequest("u1", "op", "test", null)))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("Metrics are recorded on successful call")
    void success_metricsRecorded() {
        stubFor(post(urlEqualTo("/completions"))
                .willReturn(okJson("""
                        {
                          "id":"m1","choices":[{"message":{"role":"assistant","content":"ok"},"finishReason":"stop"}],
                          "usage":{"promptTokens":3,"completionTokens":2,"totalTokens":5}
                        }
                        """)));

        adapter.complete(new AiCompletionRequest("u1", "op", "Hi", null));

        var counter = meterRegistry.find(AiGatewayMetrics.CALLS_TOTAL)
                .tag("provider", "http")
                .tag("operation", "complete")
                .tag("outcome", "success")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
