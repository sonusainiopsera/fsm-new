package com.fieldservice.identity.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link JtiDenylist} fallback, active only when Redis is unavailable.
 *
 * <p>No TTL enforcement — entries live for the JVM lifetime. For tests and local dev only;
 * never use in production where token revocation must survive restarts.
 */
@Component
@ConditionalOnMissingBean(JtiDenylist.class)
public class InMemoryJtiDenylist implements JtiDenylist {

    private final ConcurrentHashMap<String, Boolean> revoked = new ConcurrentHashMap<>();

    @Override
    public boolean isRevoked(String jti) {
        return revoked.containsKey(jti);
    }

    @Override
    public void revoke(String jti, Duration ttl) {
        revoked.put(jti, Boolean.TRUE);
    }
}
