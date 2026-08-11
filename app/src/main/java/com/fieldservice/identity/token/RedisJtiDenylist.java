package com.fieldservice.identity.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed {@link JtiDenylist} using a single {@code EXISTS} check per request.
 *
 * <p>Fail-closed: any Redis error throws {@link JtiDenylist.DenylistUnavailableException},
 * causing the token to be rejected rather than silently allowed through.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisJtiDenylist implements JtiDenylist {

    private static final Logger log = LoggerFactory.getLogger(RedisJtiDenylist.class);
    private static final String KEY_PREFIX = "jti:revoked:";

    private final StringRedisTemplate redis;

    public RedisJtiDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isRevoked(String jti) {
        try {
            Boolean exists = redis.hasKey(KEY_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("alert.jti_denylist_unavailable jti={} error={}", jti, e.getMessage());
            throw new DenylistUnavailableException("JTI denylist unavailable", e);
        }
    }

    @Override
    public void revoke(String jti, Duration ttl) {
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("alert.jti_denylist_revoke_fail jti={} error={}", jti, e.getMessage());
            throw new DenylistUnavailableException("JTI denylist revoke failed", e);
        }
    }
}
