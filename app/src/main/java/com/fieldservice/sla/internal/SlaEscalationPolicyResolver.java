package com.fieldservice.sla.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resolves the active {@link SlaEscalationPolicy} for a given event type and priority,
 * with a short-TTL cache to avoid per-event DB hits.
 *
 * <p>Cache TTL is 30 seconds. {@link #invalidate()} flushes the cache immediately;
 * it is called from admin policy-update paths so the next resolution reflects the change.
 *
 * <p>Thread-safe: per-key locks prevent thundering-herd on a cold cache.
 */
@Component
class SlaEscalationPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationPolicyResolver.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private record CacheEntry(Optional<SlaEscalationPolicy> policy, Instant expiresAt) {}

    private final SlaEscalationPolicyRepository repository;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ReentrantLock cacheLock = new ReentrantLock();

    SlaEscalationPolicyResolver(SlaEscalationPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Returns the active policy for the given event type and priority, or empty if none exists.
     */
    Optional<SlaEscalationPolicy> resolve(String eventType, String priority) {
        String key = eventType + ":" + priority;
        Instant now = clock.instant();

        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.policy();
        }

        cacheLock.lock();
        try {
            // Double-checked under lock
            cached = cache.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return cached.policy();
            }

            Optional<SlaEscalationPolicy> policy = repository.findActivePolicy(eventType, priority, now);
            cache.put(key, new CacheEntry(policy, now.plus(CACHE_TTL)));

            if (policy.isEmpty()) {
                log.debug("sla.escalation.policy_not_found eventType={} priority={}", eventType, priority);
            }
            return policy;
        } finally {
            cacheLock.unlock();
        }
    }

    /** Evicts the entire cache. Call after any policy row is written or updated. */
    void invalidate() {
        cache.clear();
        log.debug("sla.escalation.policy_cache_invalidated");
    }
}
