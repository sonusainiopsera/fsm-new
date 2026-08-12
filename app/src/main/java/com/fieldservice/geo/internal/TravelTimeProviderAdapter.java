package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelMatrixResult.Entry;
import com.fieldservice.geo.api.TravelTimePort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * HTTP adapter implementing {@link TravelTimePort}.
 *
 * <p>For each call:
 * <ol>
 *   <li>Check Redis cache per origin (per-entry cache hit → no outbound call for that origin).</li>
 *   <li>For uncached origins: issue a single batched matrix POST to the provider.</li>
 *   <li>Cache each valid response entry.</li>
 *   <li>Populate any missing/errored entries from Haversine.</li>
 *   <li>On provider failure (any kind): fall back entirely to Haversine for all uncached origins.</li>
 * </ol>
 *
 * <p>The adapter never throws; degraded results always reach the caller.
 */
class TravelTimeProviderAdapter implements TravelTimePort {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeProviderAdapter.class);

    private final RestClient restClient;
    private final TravelProviderProperties props;
    private final TravelProviderAllowList allowList;
    private final TravelCacheGateway cache;
    private final HaversineEstimator haversine;
    private final TravelMetrics metrics;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final ExecutorService executor;

    TravelTimeProviderAdapter(
            RestClient restClient,
            TravelProviderProperties props,
            TravelProviderAllowList allowList,
            TravelCacheGateway cache,
            HaversineEstimator haversine,
            TravelMetrics metrics,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter,
            Retry retry,
            ExecutorService executor) {
        this.restClient    = restClient;
        this.props         = props;
        this.allowList     = allowList;
        this.cache         = cache;
        this.haversine     = haversine;
        this.metrics       = metrics;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter   = timeLimiter;
        this.retry         = retry;
        this.executor      = executor;
    }

    @Override
    public TravelMatrixResult estimate(List<OriginRequest> origins, Coordinates destination) {
        if (origins == null || origins.isEmpty()) {
            return new TravelMatrixResult(List.of(), false);
        }

        // Separate cache-hit origins from those needing a provider call
        List<Entry> results = new ArrayList<>(origins.size());
        List<OriginRequest> uncached = new ArrayList<>();

        for (OriginRequest req : origins) {
            cache.get(req.origin(), destination).ifPresentOrElse(
                    minutes -> {
                        results.add(new Entry(req.technicianId(), minutes, false));
                        metrics.recordCacheHit(providerName());
                    },
                    () -> uncached.add(req)
            );
        }

        if (!uncached.isEmpty()) {
            List<Entry> providerEntries = fetchFromProvider(uncached, destination);
            results.addAll(providerEntries);
        }

        // Preserve original order (stable: origins list order)
        List<Entry> ordered = reorderTo(origins, results, destination);
        boolean anyDegraded = ordered.stream().anyMatch(Entry::degraded);
        return new TravelMatrixResult(ordered, anyDegraded);
    }

    // ─── Provider call ────────────────────────────────────────────────────────

    private List<Entry> fetchFromProvider(List<OriginRequest> uncached, Coordinates destination) {
        long start = System.currentTimeMillis();
        try {
            allowList.assertAllowed(props.provider().baseUrl());

            Supplier<List<Entry>> decorated = Retry.decorateSupplier(retry,
                    CircuitBreaker.decorateSupplier(circuitBreaker,
                            () -> doMatrixPost(uncached, destination)));

            CompletableFuture<List<Entry>> future = CompletableFuture.supplyAsync(decorated, executor);
            List<Entry> entries = timeLimiter.executeFutureSupplier(() -> future);

            metrics.recordCallDuration(providerName(), System.currentTimeMillis() - start);

            // Cache every non-degraded entry
            for (OriginRequest req : uncached) {
                entries.stream()
                        .filter(e -> e.technicianId().equals(req.technicianId()) && !e.degraded())
                        .findFirst()
                        .ifPresent(e -> cache.put(req.origin(), destination, e.estimatedMinutes()));
            }

            return entries;

        } catch (CallNotPermittedException e) {
            metrics.recordCallError(providerName(), "circuit_open");
            metrics.recordDegraded(providerName());
            log.warn("geo.travel.circuit_open — degrading to Haversine for {} origins", uncached.size());
        } catch (TimeoutException e) {
            metrics.recordCallError(providerName(), "timeout");
            metrics.recordDegraded(providerName());
            log.warn("geo.travel.timeout budget_ms={} origins={}",
                    props.resilience().timeLimiterTimeout().toMillis(), uncached.size());
        } catch (TravelProviderAllowList.EgressBlockedException e) {
            metrics.recordCallError(providerName(), "egress_blocked");
            metrics.recordDegraded(providerName());
            log.warn("geo.travel.egress_blocked");
        } catch (RestClientException e) {
            metrics.recordCallError(providerName(), "provider_error");
            metrics.recordDegraded(providerName());
            log.warn("geo.travel.provider_error origins={}", uncached.size());
        } catch (Exception e) {
            metrics.recordCallError(providerName(), "unexpected");
            metrics.recordDegraded(providerName());
            log.error("geo.travel.unexpected_error", e);
        }

        // Fallback: Haversine for all uncached origins
        return haversineFallback(uncached, destination);
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Entry> doMatrixPost(List<OriginRequest> uncached, Coordinates destination) {
        String apiKey = props.provider().apiKey();
        // Build request body: list of origins with technicianId and coordinates
        List<Map<String, Object>> originPayload = uncached.stream()
                .map(o -> Map.<String, Object>of(
                        "technicianId", o.technicianId().toString(),
                        "lat", o.origin().latitude(),
                        "lon", o.origin().longitude()))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "destination", Map.of("lat", destination.latitude(), "lon", destination.longitude()),
                "origins",     originPayload,
                "travelMode",  props.provider().travelMode());

        Map<?, ?> response = restClient.post()
                .uri(props.provider().baseUrl() + "/v1/matrix")
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        return parseMatrixResponse(response, uncached, destination);
    }

    @SuppressWarnings("unchecked")
    private List<Entry> parseMatrixResponse(Map<?, ?> response,
                                            List<OriginRequest> uncached,
                                            Coordinates destination) {
        if (response == null || !response.containsKey("estimates")) {
            log.warn("geo.travel.malformed_response — missing 'estimates' field");
            metrics.recordCallError(providerName(), "malformed");
            metrics.recordDegraded(providerName());
            return haversineFallback(uncached, destination);
        }

        List<?> rawEstimates;
        try {
            rawEstimates = (List<?>) response.get("estimates");
        } catch (ClassCastException e) {
            log.warn("geo.travel.malformed_response — 'estimates' is not an array");
            metrics.recordCallError(providerName(), "malformed");
            metrics.recordDegraded(providerName());
            return haversineFallback(uncached, destination);
        }

        // Build a lookup from what the provider returned
        Map<UUID, Integer> providerResults = new HashMap<>();
        for (Object raw : rawEstimates) {
            if (!(raw instanceof Map<?, ?> entry)) continue;
            Object idObj = entry.get("technicianId");
            Object minObj = entry.get("estimatedMinutes");
            if (idObj == null || minObj == null) continue;
            try {
                UUID id = UUID.fromString(idObj.toString());
                int minutes = ((Number) minObj).intValue();
                providerResults.put(id, minutes);
            } catch (Exception ignored) {
                // malformed entry — will be filled by Haversine below
            }
        }

        // Map provider results back to entries; any missing → Haversine
        List<Entry> entries = new ArrayList<>(uncached.size());
        for (OriginRequest req : uncached) {
            Integer minutes = providerResults.get(req.technicianId());
            if (minutes != null) {
                entries.add(new Entry(req.technicianId(), minutes, false));
            } else {
                // partial response — fill individually and flag degraded
                metrics.recordDegraded(providerName());
                int hMinutes = haversine.estimateMinutes(req.origin(), destination);
                entries.add(Entry.degraded(req.technicianId(), hMinutes));
            }
        }
        return entries;
    }

    // ─── Haversine fallback ───────────────────────────────────────────────────

    private List<Entry> haversineFallback(List<OriginRequest> origins, Coordinates destination) {
        return origins.stream()
                .map(req -> Entry.degraded(req.technicianId(),
                        haversine.estimateMinutes(req.origin(), destination)))
                .toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String providerName() {
        try {
            return java.net.URI.create(props.provider().baseUrl()).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Reorders result entries to match the original request order, filling any gaps with Haversine. */
    private List<Entry> reorderTo(List<OriginRequest> origins,
                                   List<Entry> results,
                                   Coordinates destination) {
        Map<UUID, Entry> byId = new HashMap<>();
        for (Entry e : results) byId.put(e.technicianId(), e);

        List<Entry> ordered = new ArrayList<>(origins.size());
        for (OriginRequest req : origins) {
            Entry e = byId.get(req.technicianId());
            if (e == null) {
                // should not happen — defensive gap fill
                ordered.add(Entry.degraded(req.technicianId(),
                        haversine.estimateMinutes(req.origin(), destination)));
            } else {
                ordered.add(e);
            }
        }
        return ordered;
    }
}
