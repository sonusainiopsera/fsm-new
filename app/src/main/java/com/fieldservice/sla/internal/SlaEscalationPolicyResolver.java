package com.fieldservice.sla.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Resolves the active escalation policy for a given (eventType, priority) pair.
 *
 * <p>A short-TTL in-process cache avoids a DB round-trip on every outbox event.
 * The cache is invalidated on any policy write (future admin API) and auto-expires
 * after {@value #TTL_MINUTES} minutes. Thread-safe via per-key lock striping.
 */
@Component
class SlaEscalationPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationPolicyResolver.class);

    static final Duration TTL = Duration.ofMinutes(2);

    private final SlaEscalationPolicyRepository repo;

    // Simple TTL cache: key → (policy, cachedAt)
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final ReentrantLock reloadLock = new ReentrantLock();

    SlaEscalationPolicyResolver(SlaEscalationPolicyRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns the resolved policy for the given event type and priority.
     * Priority-specific rows take precedence over wildcard ({@code *}) rows.
     *
     * @return empty if no active policy is found
     */
    Optional<ResolvedPolicy> resolve(String eventType, String priority) {
        String cacheKey = eventType + ":" + priority;
        CachedEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.policy();
        }

        reloadLock.lock();
        try {
            // Double-checked: may have been refreshed while waiting
            entry = cache.get(cacheKey);
            if (entry != null && !entry.isExpired()) {
                return entry.policy();
            }

            List<SlaEscalationPolicy> policies = repo.findActivePolicies(eventType, priority);
            Optional<ResolvedPolicy> resolved = policies.stream()
                    .findFirst()
                    .map(ResolvedPolicy::from);

            cache.put(cacheKey, new CachedEntry(resolved, Instant.now()));
            return resolved;
        } finally {
            reloadLock.unlock();
        }
    }

    /** Evicts all cached entries — called when a policy is written. */
    void evict() {
        cache.clear();
        log.debug("sla_escalation_policy_cache_evicted");
    }

    // ─── Value objects ────────────────────────────────────────────────────────

    record ResolvedPolicy(
            List<String> recipientRoles,
            List<String> channels,
            int          managerGraceMinutes,
            String       quietHoursStart,
            String       quietHoursEnd,
            String       zone,
            int          dedupWindowMinutes
    ) {
        static ResolvedPolicy from(SlaEscalationPolicy policy) {
            List<String> roles = Arrays.stream(policy.getRecipientRoles().split(","))
                    .map(String::strip).filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            List<String> channels = Arrays.stream(policy.getChannels().split(","))
                    .map(String::strip).filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            return new ResolvedPolicy(roles, channels,
                    policy.getManagerGraceMinutes(),
                    policy.getQuietHoursStart(), policy.getQuietHoursEnd(),
                    policy.getZone(), policy.getDedupWindowMinutes());
        }
    }

    private record CachedEntry(Optional<ResolvedPolicy> policy, Instant cachedAt) {
        boolean isExpired() {
            return Duration.between(cachedAt, Instant.now()).compareTo(TTL) > 0;
        }
    }
}
