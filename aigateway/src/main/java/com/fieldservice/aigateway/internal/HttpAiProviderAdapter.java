package com.fieldservice.aigateway.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fieldservice.aigateway.api.AiCompletionRequest;
import com.fieldservice.aigateway.api.AiCompletionResponse;
import com.fieldservice.aigateway.api.AiGatewayPort;
import com.fieldservice.aigateway.api.AiStreamChunk;
import com.fieldservice.aigateway.api.AiVisionRequest;
import com.fieldservice.aigateway.api.AiVisionResponse;
import com.fieldservice.platform.api.AiUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Provider-agnostic HTTP adapter using OpenAI-compatible request/response format.
 *
 * <p>All HTTP details (headers, payload shape, error codes) are contained here.
 * No provider-specific type appears in the public API.
 *
 * <p>Resilience order: Bulkhead → CircuitBreaker → TimeLimiter (via Future.get) →
 * Retry (on connection errors only).
 */
class HttpAiProviderAdapter implements AiGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(HttpAiProviderAdapter.class);

    private final RestClient restClient;
    private final EgressAllowList egressAllowList;
    private final SecretsProvider secrets;
    private final AiGatewayResilienceConfig resilience;
    private final AiGatewayMetrics metrics;
    private final AiGatewayProperties props;
    private final ExecutorService aiExecutor;
    private final URI providerUri;

    HttpAiProviderAdapter(
            RestClient restClient,
            EgressAllowList egressAllowList,
            SecretsProvider secrets,
            AiGatewayResilienceConfig resilience,
            AiGatewayMetrics metrics,
            AiGatewayProperties props,
            ExecutorService aiExecutor) {
        this.restClient = restClient;
        this.egressAllowList = egressAllowList;
        this.secrets = secrets;
        this.resilience = resilience;
        this.metrics = metrics;
        this.props = props;
        this.aiExecutor = aiExecutor;
        this.providerUri = URI.create(props.getProvider().getEndpoint().isBlank()
                ? "https://api.example.com/v1/chat/completions"
                : props.getProvider().getEndpoint());
        // Validate egress at startup — fail fast if misconfigured
        if (!props.getProvider().getAllowedHosts().isEmpty()) {
            egressAllowList.validate(providerUri);
        }
    }

    @Override
    public AiCompletionResponse complete(AiCompletionRequest request) {
        CompletionPayload payload = buildCompletionPayload(request);
        ProviderCompletionResponse raw = executeWithResilience("completion",
                () -> callProvider(payload, ProviderCompletionResponse.class));
        return toCompletionResponse(raw);
    }

    @Override
    public void completeStreaming(AiCompletionRequest request, Consumer<AiStreamChunk> chunkConsumer) {
        // Non-streaming fallback: execute synchronously and emit as a single chunk stream
        try {
            AiCompletionResponse response = complete(request);
            chunkConsumer.accept(AiStreamChunk.of(response.content()));
            chunkConsumer.accept(AiStreamChunk.last());
        } catch (AiUnavailableException e) {
            chunkConsumer.accept(AiStreamChunk.error());
            throw e;
        } catch (Exception e) {
            chunkConsumer.accept(AiStreamChunk.error());
            throw new AiUnavailableException("AI assistance is temporarily unavailable.", e);
        }
    }

    @Override
    public AiVisionResponse caption(AiVisionRequest request) {
        CompletionPayload payload = buildVisionPayload(request);
        ProviderCompletionResponse raw = executeWithResilience("caption",
                () -> callProvider(payload, ProviderCompletionResponse.class));
        return new AiVisionResponse(
                raw.firstContent(),
                raw.model(),
                raw.promptTokens(),
                raw.completionTokens());
    }

    // ── Resilience decorator ──────────────────────────────────────────────────

    @FunctionalInterface
    private interface AdapterCall<T> {
        T call() throws Exception;
    }

    private <T> T executeWithResilience(String operation, AdapterCall<T> call) {
        CircuitBreaker cb = resilience.circuitBreaker();
        Bulkhead bh = resilience.bulkhead();
        Retry retry = resilience.retry();
        long timeLimitMs = resilience.timeLimitMs();

        // 1. Bulkhead
        if (!bh.tryAcquirePermission()) {
            metrics.recordRejected(operation, "bulkhead_full");
            throw new AiUnavailableException("AI service is at capacity.");
        }
        try {
            // 2. Circuit breaker
            try {
                cb.acquirePermission();
            } catch (CallNotPermittedException e) {
                metrics.recordRejected(operation, "circuit_open");
                throw new AiUnavailableException("AI service circuit is open.", e);
            }

            long start = System.currentTimeMillis();
            try {
                // 3. Execute with retry on connection errors and time limit
                T result = Retry.decorateCallable(retry, () -> {
                    CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return call.call();
                        } catch (Exception e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    }, aiExecutor);
                    try {
                        return future.get(timeLimitMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        future.cancel(true);
                        throw te;
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof RuntimeException re) throw re;
                        throw new IOException("Provider call failed", cause);
                    }
                }).call();

                long elapsed = System.currentTimeMillis() - start;
                cb.onSuccess(elapsed, TimeUnit.MILLISECONDS);
                return result;

            } catch (TimeoutException te) {
                long elapsed = System.currentTimeMillis() - start;
                cb.onError(elapsed, TimeUnit.MILLISECONDS, te);
                metrics.recordFailure(operation, elapsed, "timeout");
                throw new AiUnavailableException("AI request timed out.", te);
            } catch (AiUnavailableException e) {
                long elapsed = System.currentTimeMillis() - start;
                cb.onError(elapsed, TimeUnit.MILLISECONDS, e);
                metrics.recordFailure(operation, elapsed, "unavailable");
                throw e;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                cb.onError(elapsed, TimeUnit.MILLISECONDS, e);
                metrics.recordFailure(operation, elapsed, "error");
                throw new AiUnavailableException("AI assistance is temporarily unavailable.", e);
            }
        } finally {
            bh.releasePermission();
        }
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private <T> T callProvider(CompletionPayload payload, Class<T> responseType) {
        egressAllowList.validate(providerUri);
        String apiKey = secrets.getApiKey();

        T response = restClient.post()
                .uri(providerUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, resp) -> {
                            // Never include upstream status text in the exception message
                            throw new AiUnavailableException(
                                    "AI provider returned an error response.");
                        })
                .body(responseType);

        if (response == null) {
            throw new AiUnavailableException("AI provider returned an empty body.");
        }
        return response;
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private CompletionPayload buildCompletionPayload(AiCompletionRequest request) {
        String model = props.getProvider().getModel();
        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : props.getProvider().getMaxTokens();

        List<CompletionPayload.Message> messages = request.systemPrompt() != null && !request.systemPrompt().isBlank()
                ? List.of(
                        new CompletionPayload.Message("system", request.systemPrompt()),
                        new CompletionPayload.Message("user", request.userMessage()))
                : List.of(new CompletionPayload.Message("user", request.userMessage()));

        return new CompletionPayload(model, messages, maxTokens, false);
    }

    private CompletionPayload buildVisionPayload(AiVisionRequest request) {
        String model = props.getProvider().getModel();
        int maxTokens = props.getProvider().getMaxTokens();
        String b64 = Base64.getEncoder().encodeToString(request.imageBytes());
        String dataUri = "data:" + request.mimeType() + ";base64," + b64;

        CompletionPayload.VisionContent visionContent = new CompletionPayload.VisionContent(
                request.prompt(), dataUri);
        return new CompletionPayload(model,
                List.of(new CompletionPayload.Message("user", visionContent)),
                maxTokens, false);
    }

    private AiCompletionResponse toCompletionResponse(ProviderCompletionResponse raw) {
        return new AiCompletionResponse(
                raw.firstContent(),
                raw.model(),
                raw.promptTokens(),
                raw.completionTokens());
    }

    // ── Internal DTOs (OpenAI-compatible wire format) ─────────────────────────

    record CompletionPayload(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens") int maxTokens,
            boolean stream) {

        record Message(String role, Object content) {}

        record VisionContent(
                @JsonProperty("type") String type,
                @JsonProperty("text") String text,
                @JsonProperty("image_url") ImageUrl imageUrl) {

            record ImageUrl(String url) {}

            // Convenience constructor for text+image combo
            VisionContent(String text, String dataUri) {
                this("text", text, new ImageUrl(dataUri));
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderCompletionResponse(
            String id,
            String model,
            List<Choice> choices,
            @JsonProperty("usage") Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(
                @JsonProperty("message") Message message,
                @JsonProperty("finish_reason") String finishReason) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            record Message(String role, String content) {}
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(
                @JsonProperty("prompt_tokens") int promptTokens,
                @JsonProperty("completion_tokens") int completionTokens) {}

        String firstContent() {
            if (choices == null || choices.isEmpty()) {
                throw new AiUnavailableException("AI provider returned no choices.");
            }
            String content = choices.get(0).message().content();
            if (content == null || content.isBlank()) {
                throw new AiUnavailableException("AI provider returned an empty completion.");
            }
            return content;
        }

        int promptTokens() { return usage != null ? usage.promptTokens() : 0; }
        int completionTokens() { return usage != null ? usage.completionTokens() : 0; }
    }
}
