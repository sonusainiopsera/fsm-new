package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamCallback;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.platform.api.exception.AiUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * HTTP adapter for the AI provider. Package-private — callers use {@link AiGatewayPort}.
 *
 * <p>Every call is executed on the dedicated AI executor (virtual threads), wrapped in
 * CircuitBreaker → Bulkhead → TimeLimiter → Retry, and validated against the egress allow-list.
 */
class HttpAiProviderAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAiProviderAdapter.class);
    private static final String PROVIDER = "configured";

    private final RestClient restClient;
    private final AiGatewayProperties properties;
    private final EgressAllowList egressAllowList;
    private final SecretsProvider secretsProvider;
    private final AiGatewayMetrics metrics;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final ExecutorService aiExecutor;

    HttpAiProviderAdapter(
            RestClient restClient,
            AiGatewayProperties properties,
            EgressAllowList egressAllowList,
            SecretsProvider secretsProvider,
            AiGatewayMetrics metrics,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            TimeLimiter timeLimiter,
            Retry retry,
            ExecutorService aiExecutor) {
        this.restClient = restClient;
        this.properties = properties;
        this.egressAllowList = egressAllowList;
        this.secretsProvider = secretsProvider;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.timeLimiter = timeLimiter;
        this.retry = retry;
        this.aiExecutor = aiExecutor;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        return executeWithGuardrails("complete", () -> doComplete(request));
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, AiStreamCallback callback) {
        try {
            executeWithGuardrails("complete_streaming", () -> {
                doCompleteStreaming(request, callback);
                return null;
            });
        } catch (AiUnavailableException e) {
            callback.onError(e);
            throw e;
        }
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        return executeWithGuardrails("caption", () -> doCaption(request));
    }

    private <T> T executeWithGuardrails(String operation, Supplier<T> call) {
        egressAllowList.assertAllowed(properties.provider().baseUrl());

        long start = System.currentTimeMillis();
        try {
            Supplier<T> decorated = Retry.decorateSupplier(retry,
                    Bulkhead.decorateSupplier(bulkhead,
                            CircuitBreaker.decorateSupplier(circuitBreaker, call)));

            CompletableFuture<T> future = CompletableFuture.supplyAsync(decorated, aiExecutor);
            T result = timeLimiter.executeFutureSupplier(() -> future);

            metrics.recordSuccess(PROVIDER, operation, System.currentTimeMillis() - start, 0, 0);
            return result;

        } catch (CallNotPermittedException e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "circuit_open");
            log.warn("ai_circuit_open operation={}", operation);
            throw new AiUnavailableException(operation, e);
        } catch (BulkheadFullException e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "bulkhead_full");
            log.warn("ai_bulkhead_full operation={}", operation);
            throw new AiUnavailableException(operation, e);
        } catch (TimeoutException e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "timeout");
            log.warn("ai_timeout operation={} budget_ms={}", operation,
                    properties.resilience().timeLimiter().timeoutDuration().toMillis());
            throw new AiUnavailableException(operation, e);
        } catch (EgressAllowList.EgressBlockedException e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "egress_blocked");
            throw new AiUnavailableException(operation, e);
        } catch (RestClientException e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "provider_error");
            log.warn("ai_provider_error operation={}", operation);
            throw new AiUnavailableException(operation, e);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            metrics.recordFailure(PROVIDER, operation, System.currentTimeMillis() - start, "unexpected");
            log.error("ai_unexpected_error operation={}", operation, e);
            throw new AiUnavailableException(operation, e);
        }
    }

    @SuppressWarnings("unchecked")
    private AiCompletionResponse doComplete(AiCompletionRequest request) {
        String apiKey = secretsProvider.getApiKey();
        Map<?, ?> body = restClient.post()
                .uri(properties.provider().baseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(buildCompletionBody(request))
                .retrieve()
                .body(Map.class);

        return parseCompletionResponse(body);
    }

    private void doCompleteStreaming(AiCompletionRequest request, AiStreamCallback callback) {
        // Streaming is implemented as a non-streaming call in this baseline — full streaming
        // with SSE parsing is deferred to the streaming story.
        AiCompletionResponse response = doComplete(request);
        callback.onToken(response.content());
        callback.onComplete();
    }

    @SuppressWarnings("unchecked")
    private AiVisionResponse doCaption(AiVisionRequest request) {
        String apiKey = secretsProvider.getApiKey();
        Map<?, ?> body = restClient.post()
                .uri(properties.provider().baseUrl() + "/v1/vision/caption")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                        "object_key", request.objectStoreKey(),
                        "prompt", request.prompt()))
                .retrieve()
                .body(Map.class);

        return parseVisionResponse(body);
    }

    private Map<String, Object> buildCompletionBody(AiCompletionRequest request) {
        return Map.of(
                "model", "provider-default",
                "max_tokens", request.maxTokens(),
                "messages", request.messages().stream()
                        .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private AiCompletionResponse parseCompletionResponse(Map<?, ?> body) {
        if (body == null || !body.containsKey("choices")) {
            throw new AiUnavailableException("complete");
        }
        try {
            var choices = (java.util.List<?>) body.get("choices");
            if (choices == null || choices.isEmpty()) throw new AiUnavailableException("complete");
            var choice = (Map<?, ?>) choices.get(0);
            var message = (Map<?, ?>) choice.get("message");
            String content = (String) message.get("content");

            Map<?, ?> usage = (Map<?, ?>) body.getOrDefault("usage", Map.of());
            int promptTokens = toInt(usage.get("prompt_tokens"));
            int completionTokens = toInt(usage.get("completion_tokens"));
            return new AiCompletionResponse(content, promptTokens, completionTokens);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("complete", e);
        }
    }

    @SuppressWarnings("unchecked")
    private AiVisionResponse parseVisionResponse(Map<?, ?> body) {
        if (body == null || !body.containsKey("caption")) {
            throw new AiUnavailableException("caption");
        }
        try {
            String caption = (String) body.get("caption");
            int tokens = toInt(body.get("tokens_used"));
            return new AiVisionResponse(caption, tokens);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("caption", e);
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }
}
