package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCapExceededException;
import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
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

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * HTTP implementation of the AI provider call, wrapped with Resilience4j guardrails.
 *
 * <p>All calls execute on a dedicated virtual-thread executor — never on the Tomcat pool.
 * The SSRF allow-list is validated before opening a socket.
 */
class HttpAiProviderAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAiProviderAdapter.class);
    private static final String PROVIDER = "http";

    private final RestClient restClient;
    private final EgressAllowList egressAllowList;
    private final SecretsProvider secrets;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final ExecutorService executor;
    private final AiGatewayMetrics metrics;
    private final String baseUrl;
    private final double costPerToken;

    HttpAiProviderAdapter(RestClient restClient,
                          EgressAllowList egressAllowList,
                          SecretsProvider secrets,
                          CircuitBreaker circuitBreaker,
                          Bulkhead bulkhead,
                          TimeLimiter timeLimiter,
                          Retry retry,
                          ExecutorService executor,
                          AiGatewayMetrics metrics,
                          String baseUrl,
                          double costPerToken) {
        this.restClient = restClient;
        this.egressAllowList = egressAllowList;
        this.secrets = secrets;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.timeLimiter = timeLimiter;
        this.retry = retry;
        this.executor = executor;
        this.metrics = metrics;
        this.baseUrl = baseUrl;
        this.costPerToken = costPerToken;
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        egressAllowList.validate(baseUrl);
        return executeWithResilience("complete", () -> doComplete(request));
    }

    @Override
    public Stream<AiStreamChunk> completeStreaming(AiCompletionRequest request) {
        throw new AiUnavailableException("Streaming completion is not yet implemented in this provider adapter");
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        egressAllowList.validate(baseUrl);
        return executeWithResilience("caption", () -> doCaption(request));
    }

    // ── provider calls ─────────────────────────────────────────────────────────

    private AiCompletionResponse doComplete(AiCompletionRequest req) {
        ProviderCompletionRequest body = new ProviderCompletionRequest(
                List.of(new ProviderMessage("user", req.prompt())));

        ProviderCompletionResponse resp = restClient.post()
                .uri("/completions")
                .header("Authorization", "Bearer " + secrets.getApiKey())
                .body(body)
                .retrieve()
                .body(ProviderCompletionResponse.class);

        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            throw new AiUnavailableException("AI provider returned an empty or malformed response");
        }

        ProviderChoice choice = resp.choices().get(0);
        String content = (choice.message() != null) ? choice.message().content() : null;
        if (content == null) {
            throw new AiUnavailableException("AI provider returned a malformed response body");
        }

        int totalTokens = (resp.usage() != null) ? resp.usage().totalTokens() : 0;
        int promptTokens = (resp.usage() != null) ? resp.usage().promptTokens() : 0;
        int completionTokens = (resp.usage() != null) ? resp.usage().completionTokens() : 0;
        boolean complete = "stop".equals(choice.finishReason());

        return new AiCompletionResponse(content, promptTokens, completionTokens, complete);
    }

    private AiVisionResponse doCaption(AiVisionRequest req) {
        ProviderVisionRequest body = new ProviderVisionRequest(
                req.imageUrl(),
                req.prompt() != null ? req.prompt() : "Describe this image.");

        ProviderVisionResponse resp = restClient.post()
                .uri("/captions")
                .header("Authorization", "Bearer " + secrets.getApiKey())
                .body(body)
                .retrieve()
                .body(ProviderVisionResponse.class);

        if (resp == null || resp.caption() == null) {
            throw new AiUnavailableException("AI provider returned an empty or malformed vision response");
        }

        return new AiVisionResponse(resp.caption(), resp.description(), resp.tokensUsed());
    }

    // ── resilience wrapper ──────────────────────────────────────────────────────

    private <T> T executeWithResilience(String operation,
                                         java.util.concurrent.Callable<T> call) {
        Instant start = Instant.now();
        String outcome = "success";
        int totalTokens = 0;
        try {
            // Decorate: retry → circuit breaker → bulkhead → timed future on virtual thread
            java.util.concurrent.Callable<T> decorated =
                    Retry.decorateCallable(retry,
                    CircuitBreaker.decorateCallable(circuitBreaker,
                    Bulkhead.decorateCallable(bulkhead, call)));

            CompletableFuture<T> future = timeLimiter.executeCompletionStage(
                    executor, () -> CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return decorated.call();
                                } catch (RuntimeException e) {
                                    throw e;
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, executor));

            return future.get();

        } catch (TimeoutException e) {
            outcome = "timeout";
            log.warn("ai_gateway operation={} outcome=timeout provider={}", operation, PROVIDER);
            throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            outcome = "error";
            if (cause instanceof AiUnavailableException aue) {
                throw aue;
            }
            if (cause instanceof AiCapExceededException ace) {
                throw ace;
            }
            if (cause instanceof CallNotPermittedException) {
                outcome = "circuit-open";
                log.warn("ai_gateway operation={} outcome=circuit-open provider={}", operation, PROVIDER);
                throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", cause);
            }
            if (cause instanceof BulkheadFullException) {
                outcome = "bulkhead-full";
                log.warn("ai_gateway operation={} outcome=bulkhead-full provider={}", operation, PROVIDER);
                throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", cause);
            }
            if (cause instanceof RestClientException || cause instanceof IOException
                    || cause instanceof ConnectException) {
                log.warn("ai_gateway operation={} outcome=provider-error provider={}", operation, PROVIDER);
                throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", cause);
            }
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            log.error("ai_gateway operation={} outcome=unexpected-error provider={}", operation, PROVIDER);
            throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", e);
        } catch (CallNotPermittedException e) {
            outcome = "circuit-open";
            log.warn("ai_gateway operation={} outcome=circuit-open provider={}", operation, PROVIDER);
            throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", e);
        } catch (BulkheadFullException e) {
            outcome = "bulkhead-full";
            log.warn("ai_gateway operation={} outcome=bulkhead-full provider={}", operation, PROVIDER);
            throw new AiUnavailableException("AI assistance is temporarily unavailable. You can continue without it.", e);
        } finally {
            Duration latency = Duration.between(start, Instant.now());
            metrics.recordCall(PROVIDER, operation, outcome, latency, totalTokens,
                    totalTokens * costPerToken);
        }
    }

    // ── internal provider DTOs ─────────────────────────────────────────────────

    record ProviderCompletionRequest(List<ProviderMessage> messages) {}

    record ProviderMessage(String role, String content) {}

    record ProviderCompletionResponse(
            String id,
            List<ProviderChoice> choices,
            ProviderUsage usage) {}

    record ProviderChoice(ProviderMessage message, String finishReason) {}

    record ProviderUsage(int promptTokens, int completionTokens, int totalTokens) {}

    record ProviderVisionRequest(String imageUrl, String prompt) {}

    record ProviderVisionResponse(String caption, String description, int tokensUsed) {}

}
