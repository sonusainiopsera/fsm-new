package com.fieldservice.aigateway;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.internal.AiGatewayProperties;
import com.fieldservice.aigateway.internal.EgressAllowList;
import com.fieldservice.aigateway.internal.EnvironmentSecretsProvider;
import com.fieldservice.aigateway.internal.HttpAiProviderAdapter;
import com.fieldservice.aigateway.internal.AiGatewayMetrics;
import com.fieldservice.aigateway.internal.NoOpUsageCapService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock-backed integration tests for {@link HttpAiProviderAdapter}.
 * No Spring context — direct construction, real Resilience4j, real RestClient.
 */
class AiGatewayWireMockIT {

    private WireMockServer wireMock;
    private HttpAiProviderAdapter adapter;
    private AiGatewayProperties properties;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        String baseUrl = "http://localhost:" + wireMock.port();

        properties = new AiGatewayProperties(
                new AiGatewayProperties.Copilot(true, 50),
                new AiGatewayProperties.Provider(baseUrl, "test-key",
                        List.of("localhost", "127.0.0.1"),
                        Duration.ofSeconds(2), Duration.ofSeconds(10)),
                new AiGatewayProperties.Resilience(
                        new AiGatewayProperties.Resilience.CircuitBreakerConfig(50f, 4, Duration.ofSeconds(30), 3),
                        new AiGatewayProperties.Resilience.BulkheadConfig(16),
                        new AiGatewayProperties.Resilience.TimeLimiterConfig(Duration.ofSeconds(5))),
                new AiGatewayProperties.Metrics(0.002));

        var meterRegistry = new SimpleMeterRegistry();
        var egressAllowList = new EgressAllowList(properties);
        var secretsProvider = new EnvironmentSecretsProvider(properties);
        var metrics = new AiGatewayMetrics(meterRegistry, properties);

        var circuitBreaker = CircuitBreaker.of("ai-it",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50f).slidingWindowSize(4)
                        .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(3)
                        .build());
        var bulkhead = Bulkhead.of("ai-it", BulkheadConfig.custom().maxConcurrentCalls(16).build());
        var timeLimiter = TimeLimiter.of("ai-it",
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).cancelRunningFuture(true).build());
        var retry = Retry.of("ai-it",
                RetryConfig.custom().maxAttempts(1).build());
        executor = Executors.newVirtualThreadPerTaskExecutor();

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        adapter = new HttpAiProviderAdapter(restClient, properties, egressAllowList,
                secretsProvider, metrics, circuitBreaker, bulkhead, timeLimiter, retry, executor);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("success: 200 response is parsed and returned")
    void success_parsedCorrectly() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-test",
                                  "choices": [{
                                    "message": {"role":"assistant","content":"Hello from AI"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens":10,"completion_tokens":15,"total_tokens":25}
                                }
                                """)));

        AiCompletionResponse response = adapter.complete(completionRequest("user-1"));

        assertThat(response.content()).isEqualTo("Hello from AI");
        assertThat(response.promptTokens()).isEqualTo(10);
        assertThat(response.completionTokens()).isEqualTo(15);
        assertThat(response.totalTokens()).isEqualTo(25);
    }

    @Test
    @DisplayName("provider 500: throws AiUnavailableException")
    void provider500_throwsAiUnavailableException() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Internal server error\"}}")));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    AiUnavailableException ex = (AiUnavailableException) e;
                    assertThat(ex.getMessage()).doesNotContain("Internal server error");
                });
    }

    @Test
    @DisplayName("provider 429: throws AiUnavailableException")
    void provider429_throwsAiUnavailableException() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Rate limit exceeded\"}}")));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("malformed body: throws AiUnavailableException, not NullPointerException")
    void malformedBody_throwsAiUnavailableException() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"not\":\"the expected structure\"}")));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("slow response > timeout: throws AiUnavailableException (TimeLimiter)")
    void slowResponse_exceedsTimeLimiter_throwsAiUnavailableException() {
        // Use a 6s delay against a 5s timeout
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(6_000)
                        .withBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"slow\"}}],\"usage\":{}}")));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    AiUnavailableException ex = (AiUnavailableException) e;
                    assertThat(ex.getOperation()).isEqualTo("complete");
                });
    }

    @Test
    @DisplayName("connection reset: throws AiUnavailableException")
    void connectionReset_throwsAiUnavailableException() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("error response body is never leaked in exception message")
    void errorBody_notLeakedInException() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("SENSITIVE_INTERNAL_DETAILS")));

        assertThatThrownBy(() -> adapter.complete(completionRequest("user-1")))
                .isInstanceOf(AiUnavailableException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("SENSITIVE_INTERNAL_DETAILS");
                    if (e.getCause() != null) {
                        assertThat(e.getCause().getMessage()).doesNotContain("SENSITIVE_INTERNAL_DETAILS");
                    }
                });
    }

    private static AiCompletionRequest completionRequest(String userId) {
        return new AiCompletionRequest(userId, "You are helpful.",
                List.of(new AiCompletionRequest.AiMessage(
                        AiCompletionRequest.AiMessage.Role.USER, "Hello")), 100);
    }
}
