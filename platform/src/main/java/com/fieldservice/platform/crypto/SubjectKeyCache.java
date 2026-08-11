package com.fieldservice.platform.crypto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, TTL-limited in-memory cache for unwrapped data keys.
 *
 * <p>Prevents issuing one key-management call per encrypted row when reading a
 * batch of rows for the same subject. Keys are held in heap memory only — they
 * are never serialised, written to disk, or included in any event payload.
 *
 * <p>Cache entries are evicted when:
 * <ul>
 *   <li>the TTL expires (checked lazily on access)</li>
 *   <li>the subject's key is rotated or destroyed (explicit eviction)</li>
 *   <li>the cache reaches its maximum size (LRU eviction)</li>
 * </ul>
 */
public final class SubjectKeyCache {

    static final int DEFAULT_MAX_SIZE = 512;
    static final long DEFAULT_TTL_MS  = 5 * 60 * 1000L; // 5 minutes

    private final int maxSize;
    private final long ttlMs;

    private final Map<String, CacheEntry> store;

    public SubjectKeyCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MS);
    }

    public SubjectKeyCache(int maxSize, long ttlMs) {
        this.maxSize = maxSize;
        this.ttlMs   = ttlMs;
        this.store   = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > SubjectKeyCache.this.maxSize;
            }
        };
    }

    public synchronized void put(String subjectType, UUID subjectId, int keyVersion, DataKey key) {
        store.put(cacheKey(subjectType, subjectId, keyVersion),
                new CacheEntry(key, System.currentTimeMillis()));
    }

    public synchronized DataKey get(String subjectType, UUID subjectId, int keyVersion) {
        CacheEntry entry = store.get(cacheKey(subjectType, subjectId, keyVersion));
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.loadTime > ttlMs) {
            store.remove(cacheKey(subjectType, subjectId, keyVersion));
            return null;
        }
        return entry.key;
    }

    /** Evicts all cached versions for the subject (called on rotate and destroy). */
    public synchronized void evict(String subjectType, UUID subjectId) {
        String prefix = subjectType + ":" + subjectId + ":";
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public synchronized int size() {
        return store.size();
    }

    private static String cacheKey(String subjectType, UUID subjectId, int keyVersion) {
        return subjectType + ":" + subjectId + ":" + keyVersion;
    }

    private record CacheEntry(DataKey key, long loadTime) {}
}
