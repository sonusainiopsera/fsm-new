package com.fieldservice.identity.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed JWT ID denylist for token revocation.
 *
 * <p>Key format: {@code auth:jti:{jti}} — one key per revoked token.
 *
 * <p>Fail-closed contract: if Redis is unavailable, {@link #isDenied(String)} throws
 * rather than returning false, so the caller rejects the request rather than permitting
 * an unverified token.
 *
 * <p>Insertion is performed by the logout story (WO future); this class only provides
 * the lookup path and a helper for tests.
 */
public class JtiDenylist {

    private static final Logger log = LoggerFactory.getLogger(JtiDenylist.class);

    private static final String KEY_PREFIX = "auth:jti:";

    private final StringRedisTemplate redis;

    public JtiDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Returns {@code true} if the given {@code jti} is on the denylist.
     *
     * @throws JtiDenylistUnavailableException when Redis is unreachable — fail-closed
     */
    public boolean isDenied(String jti) {
        try {
            Boolean exists = redis.hasKey(KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("jti_denylist_unavailable jti={} — rejecting request fail-closed", jti, e);
            throw new JtiDenylistUnavailableException(e);
        }
    }

    /**
     * Adds a JTI to the denylist with a TTL equal to the token's residual lifetime.
     * Called by the logout flow; if the token is already expired, the TTL is zero and
     * the key is not stored.
     *
     * @param jti       the token identifier
     * @param expiresAt when the token naturally expires; must be in the future
     */
    public void deny(String jti, Instant expiresAt) {
        long residualSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
        if (residualSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(residualSeconds));
    }

    /**
     * Thrown when Redis is unreachable during a denylist check (fail-closed signal).
     */
    public static class JtiDenylistUnavailableException extends RuntimeException {
        public JtiDenylistUnavailableException(Throwable cause) {
            super("JTI denylist unavailable — request rejected fail-closed", cause);
        }
    }
}
