package com.fieldservice.copilot.internal;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces per-user concurrent stream limit using a lock-free atomic counter map.
 * Thread-safe; designed for virtual-thread concurrency.
 */
@Component
class ConcurrentStreamLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final CopilotStreamProperties properties;

    ConcurrentStreamLimiter(CopilotStreamProperties properties) {
        this.properties = properties;
    }

    /**
     * Attempts to acquire a stream slot for the given user.
     * @return true if slot acquired (caller must call {@link #release} when stream ends)
     */
    boolean tryAcquire(String userId) {
        AtomicInteger counter = counts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        int current;
        do {
            current = counter.get();
            if (current >= properties.getMaxConcurrentPerUser()) {
                return false;
            }
        } while (!counter.compareAndSet(current, current + 1));
        return true;
    }

    void release(String userId) {
        AtomicInteger counter = counts.get(userId);
        if (counter != null) {
            int val = counter.decrementAndGet();
            if (val <= 0) {
                counts.remove(userId, counter);
            }
        }
    }
}
