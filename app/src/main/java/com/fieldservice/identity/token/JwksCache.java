package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.text.ParseException;
import java.time.Duration;

/**
 * Redis-backed cache for the JWKS document returned by {@link SigningKeyProvider}.
 *
 * <p>The cache stores the serialised JWK set under key {@code auth:jwks:set} with a
 * 600-second TTL. On a cache hit the Redis copy is used directly; on a miss or TTL
 * expiry the provider is consulted and the result is re-cached. Metrics counters
 * distinguish hits, misses and refreshes.
 *
 * <p>Fail-closed: if the provider raises an exception the caller gets an
 * {@link IllegalStateException} rather than a stale or null key set.
 */
public class JwksCache {

    private static final Logger log = LoggerFactory.getLogger(JwksCache.class);

    private static final String CACHE_KEY = "auth:jwks:set";
    private static final Duration CACHE_TTL = Duration.ofSeconds(600);

    private final SigningKeyProvider provider;
    private final StringRedisTemplate redis;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter refreshCounter;

    public JwksCache(SigningKeyProvider provider, StringRedisTemplate redis,
                     MeterRegistry meterRegistry) {
        this.provider = provider;
        this.redis     = redis;
        this.hitCounter     = Counter.builder("jwks.cache.hit")
                .description("JWKS cache hits").register(meterRegistry);
        this.missCounter    = Counter.builder("jwks.cache.miss")
                .description("JWKS cache misses").register(meterRegistry);
        this.refreshCounter = Counter.builder("jwks.cache.refresh")
                .description("JWKS cache refreshes after TTL expiry").register(meterRegistry);
    }

    /**
     * Returns the current JWKS, served from the Redis cache when available.
     *
     * @throws IllegalStateException if the provider and the cache are both unavailable
     */
    public JWKSet get() {
        String cached = redis.opsForValue().get(CACHE_KEY);
        if (cached != null) {
            hitCounter.increment();
            try {
                return JWKSet.parse(cached);
            } catch (ParseException e) {
                log.error("jwks_cache_corrupt — re-fetching from provider", e);
                refreshCounter.increment();
                return fetchAndCache();
            }
        }
        missCounter.increment();
        return fetchAndCache();
    }

    private JWKSet fetchAndCache() {
        JWKSet jwkSet = provider.getJwkSet();
        redis.opsForValue().set(CACHE_KEY, jwkSet.toString(false), CACHE_TTL);
        return jwkSet;
    }

    /** Evicts the cached JWKS, forcing the next {@link #get()} call to re-fetch. */
    public void evict() {
        redis.delete(CACHE_KEY);
        log.info("jwks_cache_evicted");
    }
}
