package com.fieldservice.dispatch.scoring;

import com.fieldservice.dispatch.scoring.persistence.DispatchScoringConfigRepository;
import com.fieldservice.dispatch.scoring.persistence.DispatchScoringWeight;
import com.fieldservice.dispatch.scoring.persistence.DispatchScoringWeightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads {@link ScoringWeights} from the database with a short-lived in-process cache.
 *
 * <p>The cache TTL is {@value #CACHE_TTL_SECONDS} seconds. A weight change in the database
 * takes effect within one TTL window without requiring a redeploy.
 *
 * <p>Startup validation rejects negative weight values and an exponent &le; 1.
 * Invalid configuration fails fast with a {@link IllegalStateException} rather than
 * silently inverting a factor.
 */
@Component
public class ScoringWeightsLoader {

    private static final Logger log = LoggerFactory.getLogger(ScoringWeightsLoader.class);
    static final int CACHE_TTL_SECONDS = 30;
    private static final String EXPONENT_KEY = "WORKLOAD_PENALTY_EXPONENT";
    private static final double DEFAULT_EXPONENT = 2.0;

    private final DispatchScoringWeightRepository weightRepo;
    private final DispatchScoringConfigRepository configRepo;

    private volatile ScoringWeights cached;
    private volatile Instant cachedAt = Instant.EPOCH;
    private final ReentrantLock lock = new ReentrantLock();

    public ScoringWeightsLoader(DispatchScoringWeightRepository weightRepo,
                                DispatchScoringConfigRepository configRepo) {
        this.weightRepo = weightRepo;
        this.configRepo = configRepo;
    }

    /**
     * Returns the current weights snapshot, loading from DB if the cache is stale.
     */
    @Transactional(readOnly = true)
    public ScoringWeights load() {
        Instant now = Instant.now();
        if (cached != null && Duration.between(cachedAt, now).getSeconds() < CACHE_TTL_SECONDS) {
            return cached;
        }
        lock.lock();
        try {
            // Re-check under lock in case another thread refreshed while we waited
            now = Instant.now();
            if (cached != null && Duration.between(cachedAt, now).getSeconds() < CACHE_TTL_SECONDS) {
                return cached;
            }
            ScoringWeights loaded = loadFromDb();
            cached   = loaded;
            cachedAt = now;
            log.debug("Scoring weights refreshed: {} active entries, exponent={}",
                    loaded.entries().stream().filter(ScoringWeights.WeightEntry::active).count(),
                    loaded.workloadPenaltyExponent());
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    /** Invalidates the cache so the next call reloads from DB. */
    public void invalidate() {
        lock.lock();
        try {
            cached   = null;
            cachedAt = Instant.EPOCH;
        } finally {
            lock.unlock();
        }
    }

    private ScoringWeights loadFromDb() {
        List<DispatchScoringWeight> rows = weightRepo.findAllByOrderByFactorCode();

        for (DispatchScoringWeight row : rows) {
            if (row.getWeight().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException(
                        "Negative scoring weight for factor '" + row.getFactorCode()
                        + "': " + row.getWeight() + ". Fix dispatch_scoring_weight before restarting.");
            }
        }

        List<ScoringWeights.WeightEntry> entries = rows.stream()
                .map(r -> new ScoringWeights.WeightEntry(
                        r.getFactorCode(),
                        r.getWeight().doubleValue(),
                        r.isActive()))
                .toList();

        double exponent = configRepo.findByConfigKey(EXPONENT_KEY)
                .map(c -> c.getConfigValue().doubleValue())
                .orElse(DEFAULT_EXPONENT);

        if (exponent <= 1.0) {
            throw new IllegalStateException(
                    "WORKLOAD_PENALTY_EXPONENT must be > 1; got " + exponent
                    + ". Fix dispatch_scoring_config.");
        }

        return new ScoringWeights(entries, exponent);
    }
}
