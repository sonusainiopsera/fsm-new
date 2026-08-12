package com.fieldservice.platform.crypto;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, TTL-limited in-memory cache of unwrapped per-subject data keys.
 *
 * <p>Keys are held in memory only; they are never serialised, persisted or included
 * in any log or event payload.  The cache is evicted on {@link #evict} (rotate or destroy)
 * to prevent stale key use.
 *
 * <h2>Sizing</h2>
 * {@code maxEntries} bounds memory consumption.  When the cache is full an eviction
 * sweep removes all expired entries; if still full, the oldest entry is dropped.
 * Typical deployment: 512 entries × ~32 bytes key = ~16 KB resident.
 *
 * <h2>TTL</h2>
 * Default TTL is 15 minutes.  Entries are not eagerly removed on expiry; they are
 * evicted lazily on the next access for the same cache key, or on a sweep triggered
 * by capacity pressure.
 */
public final class SubjectKeyCache {

    private static final int     DEFAULT_MAX_ENTRIES = 512;
    private static final long    DEFAULT_TTL_SECONDS = 15 * 60L;

    private final int  maxEntries;
    private final long ttlSeconds;

    private final ConcurrentHashMap<CacheKey, CachedEntry> store;

    public SubjectKeyCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_SECONDS);
    }

    public SubjectKeyCache(int maxEntries, long ttlSeconds) {
        this.maxEntries = maxEntries;
        this.ttlSeconds = ttlSeconds;
        this.store      = new ConcurrentHashMap<>(maxEntries);
    }

    /**
     * Returns a cached key spec if one exists and has not expired.
     */
    public Optional<SubjectKeySpec> get(SubjectRef subject, int keyVersion) {
        CacheKey key = new CacheKey(subject.subjectType(), subject.subjectId(), keyVersion);
        CachedEntry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.spec());
    }

    /**
     * Stores a key spec in the cache, evicting expired or excess entries as needed.
     */
    public void put(SubjectRef subject, SubjectKeySpec spec) {
        CacheKey key = new CacheKey(subject.subjectType(), subject.subjectId(), spec.keyVersion());
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        if (store.size() >= maxEntries) {
            evictExpired();
            if (store.size() >= maxEntries) {
                evictOldest();
            }
        }
        store.put(key, new CachedEntry(spec, expiresAt));
    }

    /**
     * Removes ALL cached entries for the given subject (all versions).
     * Must be called on rotate and destroy to prevent stale key use.
     */
    public void evict(SubjectRef subject) {
        String type = subject.subjectType();
        UUID   id   = subject.subjectId();
        store.keySet().removeIf(k -> type.equals(k.subjectType()) && id.equals(k.subjectId()));
    }

    /** Returns the current number of cached entries (including expired ones not yet swept). */
    public int size() {
        return store.size();
    }

    // ---- private helpers ---------------------------------------------------

    private void evictExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<CacheKey, CachedEntry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue().expiresAt())) {
                it.remove();
            }
        }
    }

    private void evictOldest() {
        store.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> a.expiresAt().compareTo(b.expiresAt())))
                .map(Map.Entry::getKey)
                .ifPresent(store::remove);
    }

    // ---- inner records -----------------------------------------------------

    private record CacheKey(String subjectType, UUID subjectId, int keyVersion) {}

    private record CachedEntry(SubjectKeySpec spec, Instant expiresAt) {}
}
