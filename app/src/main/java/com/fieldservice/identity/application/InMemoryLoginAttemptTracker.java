package com.fieldservice.identity.application;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link LoginAttemptTracker} for environments without Redis (unit tests,
 * local development without a Redis container). Not suitable for multi-replica deployments.
 *
 * <p>Registered by {@link com.fieldservice.identity.config.PasswordEncoderConfig} as a
 * {@code @ConditionalOnMissingBean} fallback when no {@code StringRedisTemplate} is present.
 */
public class InMemoryLoginAttemptTracker implements LoginAttemptTracker {

    private record Slot(int count, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Slot> store = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final int ttlSeconds;

    public InMemoryLoginAttemptTracker(int maxFailures, int ttlSeconds) {
        this.maxFailures = maxFailures;
        this.ttlSeconds  = ttlSeconds;
    }

    @Override
    public int recordFailure(String emailHash) {
        Slot updated = store.compute(emailHash, (k, slot) -> {
            if (slot == null || Instant.now().isAfter(slot.expiresAt())) {
                return new Slot(1, Instant.now().plusSeconds(ttlSeconds));
            }
            return new Slot(slot.count() + 1, slot.expiresAt());
        });
        return updated.count();
    }

    @Override
    public void recordSuccess(String emailHash) {
        store.remove(emailHash);
    }

    @Override
    public boolean isLocked(String emailHash) {
        Slot slot = store.get(emailHash);
        if (slot == null || Instant.now().isAfter(slot.expiresAt())) {
            return false;
        }
        return slot.count() >= maxFailures;
    }
}
