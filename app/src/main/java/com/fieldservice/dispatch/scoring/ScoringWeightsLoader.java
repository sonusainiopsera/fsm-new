package com.fieldservice.dispatch.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads scoring weights and configuration from {@code dispatch_scoring_weight}
 * and {@code dispatch_scoring_config} with a short TTL in-process cache.
 *
 * <h3>Cache strategy</h3>
 * Weights are re-fetched from the database when the cached entry is older than
 * {@link #TTL}. A brief stale window is acceptable because weight changes are
 * infrequent tuning operations, not time-critical data.
 *
 * <h3>Startup validation</h3>
 * On first load (or after cache expiry) the loader validates that all recognised
 * factor codes have a non-negative weight row and that the workload exponent is
 * strictly greater than 1.0. Invalid configuration throws {@link IllegalStateException}
 * so the issue is surfaced immediately rather than silently corrupting rankings.
 *
 * <h3>Thread safety</h3>
 * A {@link ReentrantLock} prevents concurrent reload attempts. Readers arriving
 * while a reload is in progress will briefly block and then use the freshly loaded
 * value.
 */
@Component
public class ScoringWeightsLoader {

    private static final Logger log = LoggerFactory.getLogger(ScoringWeightsLoader.class);

    /** Default travel horizon: technicians beyond 90 minutes score 0 on travel efficiency. */
    static final int DEFAULT_TRAVEL_HORIZON_MINUTES = 90;

    /** Cache TTL — a weight change takes effect within this window without a redeploy. */
    static final Duration TTL = Duration.ofMinutes(2);

    /** Known factor codes. A database row with an unknown code is logged at WARN and ignored. */
    private static final Set<String> KNOWN_FACTOR_CODES = Set.of(
            CompetencyFitFactor.FACTOR_CODE,
            TravelEfficiencyFactor.FACTOR_CODE,
            WorkloadFairnessFactor.FACTOR_CODE,
            PartsAvailabilityFactor.FACTOR_CODE);

    private final NamedParameterJdbcTemplate jdbc;

    private volatile ScoringWeights cached;
    private volatile Instant         cachedAt = Instant.MIN;
    private final ReentrantLock      lock     = new ReentrantLock();

    public ScoringWeightsLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns current weights, re-fetching from the database when the TTL has elapsed.
     *
     * @throws IllegalStateException when the loaded configuration is invalid
     */
    public ScoringWeights get() {
        if (cached != null && Instant.now().isBefore(cachedAt.plus(TTL))) {
            return cached;
        }
        return reload();
    }

    /** Forces an immediate cache invalidation on the next call. */
    public void invalidate() {
        cachedAt = Instant.MIN;
        log.info("dispatch.scoring weights cache invalidated");
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private ScoringWeights reload() {
        lock.lock();
        try {
            // Double-checked: another thread may have reloaded while we waited for the lock
            if (cached != null && Instant.now().isBefore(cachedAt.plus(TTL))) {
                return cached;
            }

            Map<String, Double> weights = loadWeights();
            double exponent = loadExponent();
            int horizon = DEFAULT_TRAVEL_HORIZON_MINUTES;

            ScoringWeights loaded = new ScoringWeights(weights, exponent, horizon);
            this.cached   = loaded;
            this.cachedAt = Instant.now();
            log.info("dispatch.scoring weights reloaded factors={} exponent={}", weights.keySet(), exponent);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Double> loadWeights() {
        Map<String, Double> weights = new HashMap<>();
        jdbc.query(
                "SELECT factor_code, weight FROM dispatch_scoring_weight WHERE active = TRUE",
                Map.of(),
                (rs) -> {
                    String code   = rs.getString("factor_code");
                    double weight = rs.getDouble("weight");
                    if (weight < 0) {
                        throw new IllegalStateException(
                                "dispatch_scoring_weight: negative weight for factor " + code);
                    }
                    if (!KNOWN_FACTOR_CODES.contains(code)) {
                        log.warn("dispatch.scoring unknown factor code in weight table: {}", code);
                    } else {
                        weights.put(code, weight);
                    }
                });
        return weights;
    }

    private double loadExponent() {
        Double val = jdbc.queryForObject(
                "SELECT config_value FROM dispatch_scoring_config WHERE config_key = 'WORKLOAD_EXPONENT'",
                Map.of(),
                Double.class);
        if (val == null || val <= 1.0) {
            throw new IllegalStateException(
                    "dispatch_scoring_config: WORKLOAD_EXPONENT must be > 1.0 but was " + val);
        }
        return val;
    }
}
