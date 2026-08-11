package com.fieldservice.identity.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StreamTicketStore} for use in test environments where Redis is unavailable.
 *
 * <p>Activates only when no {@link StringRedisTemplate} bean is present (i.e., the test profile
 * excludes Redis autoconfiguration).
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} with a compare-and-remove for atomic consumption.
 */
@Component
@ConditionalOnMissingBean(StringRedisTemplate.class)
public class InMemoryStreamTicketStore implements StreamTicketStore {

    private record Entry(StreamTicketPayload payload, Instant expiresAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void store(String hashedKey, StreamTicketPayload payload, Duration ttl) {
        store.put(hashedKey, new Entry(payload, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<StreamTicketPayload> consumeAtomically(String hashedKey) {
        Entry entry = store.remove(hashedKey);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            // Entry existed but has logically expired — treat as not found
            return Optional.empty();
        }
        return Optional.of(entry.payload());
    }
}
