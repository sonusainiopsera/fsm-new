package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import com.fieldservice.geo.api.TravelMatrixEntry;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

/**
 * Resilient travel-time provider adapter.
 *
 * <p>Per-request flow:
 * <ol>
 *   <li>Check Redis for each (origin, destination) pair — cache hits bypass the provider.</li>
 *   <li>Batch all uncached pairs into a single matrix HTTP POST.</li>
 *   <li>Wrap the call in TimeLimiter → Retry → CircuitBreaker.</li>
 *   <li>Cache successful results at configured TTL.</li>
 *   <li>Fall back to Haversine for any pair that could not be resolved.</li>
 * </ol>
 *
 * <p>This adapter <em>never throws</em> to the caller — all failure modes return
 * degraded entries with {@code travelEstimateDegraded=true}.
 */
class TravelTimeProviderAdapter implements TravelTimePort {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeProviderAdapter.class);
    static final String PROVIDER = "http";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final TravelCacheGateway cache;
    private final HaversineEstimator haversine;
    private final TravelTimeEgressAllowList egressAllowList;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final ExecutorService executor;
    private final GeoMetrics metrics;

    TravelTimeProviderAdapter(
            RestClient restClient,
            String baseUrl,
            String apiKey,
            TravelCacheGateway cache,
            HaversineEstimator haversine,
            TravelTimeEgressAllowList egressAllowList,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter,
            Retry retry,
            ExecutorService executor,
            GeoMetrics metrics) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.cache = cache;
        this.haversine = haversine;
        this.egressAllowList = egressAllowList;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.retry = retry;
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public TravelMatrixResult estimateTravelTime(List<TravelCoordinate> origins,
                                                  TravelCoordinate destination) {
        List<TravelMatrixEntry> results = new ArrayList<>(origins.size());

        // Step 1: segregate cache hits from misses
        List<Integer> missIndices = new ArrayList<>();
        List<TravelCoordinate> missOrigins = new ArrayList<>();

        for (int i = 0; i < origins.size(); i++) {
            TravelCoordinate origin = origins.get(i);
            Optional<Double> cached = cache.get(origin, destination);
            if (cached.isPresent()) {
                metrics.recordCacheHit(PROVIDER);
                results.add(new TravelMatrixEntry(origin, cached.get(), false));
            } else {
                missIndices.add(i);
                missOrigins.add(origin);
                results.add(null); // placeholder — filled in step 3
            }
        }

        if (missOrigins.isEmpty()) {
            return TravelMatrixResult.of(results);
        }

        // Step 2: attempt a single batched provider call for all misses
        ProviderMatrixResponse response = tryProviderCall(missOrigins, destination);

        // Step 3: fill results; Haversine for any missing or failed entries
        int degradedCount = 0;
        for (int i = 0; i < missOrigins.size(); i++) {
            TravelCoordinate origin = missOrigins.get(i);
            int resultIndex = missIndices.get(i);

            double minutes;
            boolean degraded;

            if (response != null && response.rows() != null && i < response.rows().size()) {
                ProviderMatrixRow row = response.rows().get(i);
                minutes = row.estimatedMinutes();
                degraded = false;
                cache.put(origin, destination, minutes);
            } else {
                minutes = haversine.estimateMinutes(origin, destination);
                degraded = true;
                degradedCount++;
            }

            results.set(resultIndex, new TravelMatrixEntry(origin, minutes, degraded));
        }

        if (degradedCount > 0) {
            metrics.recordDegraded(PROVIDER, degradedCount);
        }

        return TravelMatrixResult.of(results);
    }

    private ProviderMatrixResponse tryProviderCall(List<TravelCoordinate> origins,
                                                    TravelCoordinate destination) {
        Instant start = Instant.now();
        String outcome = "success";

        try {
            java.util.concurrent.Callable<ProviderMatrixResponse> call =
                    Retry.decorateCallable(retry,
                    CircuitBreaker.decorateCallable(circuitBreaker,
                            () -> doHttpCall(origins, destination)));

            CompletableFuture<ProviderMatrixResponse> future =
                    timeLimiter.executeCompletionStage(executor,
                            () -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    return call.call();
                                } catch (RuntimeException e) {
                                    throw e;
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, executor));

            ProviderMatrixResponse resp = future.get();
            log.debug("geo.travel provider={} origins={} latencyMs={}",
                    PROVIDER, origins.size(), Duration.between(start, Instant.now()).toMillis());
            return resp;

        } catch (TimeoutException e) {
            outcome = "timeout";
            log.warn("geo.travel provider={} origins={} outcome=timeout", PROVIDER, origins.size());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CallNotPermittedException) {
                outcome = "circuit-open";
                log.warn("geo.travel provider={} outcome=circuit-open", PROVIDER);
            } else if (cause instanceof RestClientException || cause instanceof IOException) {
                outcome = "provider-error";
                log.warn("geo.travel provider={} outcome=provider-error error={}", PROVIDER,
                        cause.getMessage());
            } else {
                outcome = "error";
                log.warn("geo.travel provider={} outcome=error error={}", PROVIDER,
                        cause != null ? cause.getMessage() : "unknown");
            }
        } catch (CallNotPermittedException e) {
            outcome = "circuit-open";
            log.warn("geo.travel provider={} outcome=circuit-open", PROVIDER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            log.warn("geo.travel provider={} outcome=interrupted", PROVIDER);
        } finally {
            Duration latency = Duration.between(start, Instant.now());
            metrics.recordCall(PROVIDER, outcome, latency);
            if (!"success".equals(outcome)) {
                metrics.recordError(PROVIDER, outcome);
            }
        }
        return null;
    }

    private ProviderMatrixResponse doHttpCall(List<TravelCoordinate> origins,
                                               TravelCoordinate destination) {
        egressAllowList.validate(baseUrl);

        List<ProviderCoordinate> provOrigins = origins.stream()
                .map(o -> new ProviderCoordinate(o.latitude(), o.longitude()))
                .toList();

        ProviderMatrixRequest body = new ProviderMatrixRequest(
                provOrigins,
                new ProviderCoordinate(destination.latitude(), destination.longitude()));

        ProviderMatrixResponse resp = restClient.post()
                .uri("/matrix")
                .header("X-Api-Key", apiKey)
                .body(body)
                .retrieve()
                .body(ProviderMatrixResponse.class);

        if (resp == null || resp.rows() == null) {
            throw new RestClientException("Travel provider returned null or malformed response");
        }

        return resp;
    }

    // ── internal provider DTOs ─────────────────────────────────────────────────

    record ProviderCoordinate(double lat, double lon) {}

    record ProviderMatrixRequest(
            List<ProviderCoordinate> origins,
            ProviderCoordinate destination) {}

    record ProviderMatrixRow(int originIndex, double estimatedMinutes) {}

    record ProviderMatrixResponse(List<ProviderMatrixRow> rows) {}
}
