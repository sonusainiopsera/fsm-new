package com.fieldservice.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Duration;

/**
 * Redis-backed {@link JwksCache} with a 600-second TTL.
 *
 * <p>Emits three Micrometer counters: {@code jwks_cache_hits_total},
 * {@code jwks_cache_misses_total} and {@code jwks_cache_refreshes_total}.
 * A Redis read or write failure is logged and handled gracefully — the
 * JWKSet is fetched directly from the provider as a fallback.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisJwksCache implements JwksCache {

    private static final Logger log = LoggerFactory.getLogger(RedisJwksCache.class);
    private static final String CACHE_KEY = "jwks:verification:v1";
    static final long TTL_SECONDS = 600;

    private final SigningKeyProvider keyProvider;
    private final StringRedisTemplate redis;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter refreshCounter;

    public RedisJwksCache(SigningKeyProvider keyProvider,
                          StringRedisTemplate redis,
                          MeterRegistry meterRegistry) {
        this.keyProvider = keyProvider;
        this.redis = redis;
        this.hitCounter = Counter.builder("jwks_cache_hits_total")
                .description("Number of JWKS cache hits")
                .register(meterRegistry);
        this.missCounter = Counter.builder("jwks_cache_misses_total")
                .description("Number of JWKS cache misses")
                .register(meterRegistry);
        this.refreshCounter = Counter.builder("jwks_cache_refreshes_total")
                .description("Number of JWKS cache refreshes from provider")
                .register(meterRegistry);
    }

    @Override
    public JWKSet getJwkSet() {
        try {
            String cached = redis.opsForValue().get(CACHE_KEY);
            if (cached != null) {
                hitCounter.increment();
                return JWKSet.parse(cached);
            }
        } catch (ParseException e) {
            log.warn("alert.jwks_cache_parse_fail msg={}", e.getMessage());
        } catch (Exception e) {
            log.warn("alert.jwks_cache_read_fail msg={}", e.getMessage());
        }

        missCounter.increment();
        JWKSet jwkSet = keyProvider.getVerificationJwkSet();

        try {
            redis.opsForValue().set(CACHE_KEY, jwkSet.toString(false), Duration.ofSeconds(TTL_SECONDS));
            refreshCounter.increment();
        } catch (Exception e) {
            log.warn("alert.jwks_cache_write_fail msg={}", e.getMessage());
        }

        return jwkSet;
    }
}
