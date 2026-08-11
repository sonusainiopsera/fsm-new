package com.fieldservice.identity.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fallback implementation of {@link LoginAttemptTracker}.
 *
 * <p>Active only when no Redis-backed {@link LoginAttemptTracker} is present (e.g.
 * when Redis autoconfiguration is excluded in the {@code test} profile).
 * This implementation is correct for single-node use but has no TTL or shared state.
 * It must never be used in production.
 */
@Component
@ConditionalOnMissingBean(LoginAttemptTracker.class)
public class InMemoryLoginAttemptTracker implements LoginAttemptTracker {

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public int getCount(String emailHash) {
        AtomicInteger counter = counts.get(emailHash);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public int recordFailure(String emailHash) {
        return counts.computeIfAbsent(emailHash, k -> new AtomicInteger(0)).incrementAndGet();
    }

    @Override
    public void resetCounter(String emailHash) {
        counts.remove(emailHash);
    }
}
