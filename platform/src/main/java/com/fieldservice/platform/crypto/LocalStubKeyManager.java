package com.fieldservice.platform.crypto;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link SubjectKeyManager} for local development and tests.
 *
 * <p>Generates random 256-bit DEKs and stores them in heap memory only. There is no
 * real KMS wrapping — keys are not written to any database or external service. This
 * implementation must never run under the {@code api} or {@code worker} profiles.
 *
 * <p>Key material is held as a plain {@code byte[]} in a {@link ConcurrentHashMap}.
 * It is never logged, serialised, or included in event payloads. The {@link SubjectKeyCache}
 * passed at construction caches resolved keys to allow batch-read call-count verification.
 */
public class LocalStubKeyManager implements SubjectKeyManager {

    private record KeyRecord(byte[] keyMaterial, int version, SubjectKeyState state) {
        KeyRecord withState(SubjectKeyState s) {
            return new KeyRecord(Arrays.copyOf(keyMaterial, keyMaterial.length), version, s);
        }
    }

    private final Map<String, KeyRecord> keysBySubjectAndVersion = new ConcurrentHashMap<>();
    private final Map<String, Integer>   currentVersionBySubject = new ConcurrentHashMap<>();
    private final Map<UUID, String>      subjectTypeById         = new ConcurrentHashMap<>();
    private final SubjectKeyCache        cache;
    private final AtomicInteger          resolveCallCount        = new AtomicInteger();

    public LocalStubKeyManager() {
        this(new SubjectKeyCache());
    }

    public LocalStubKeyManager(SubjectKeyCache cache) {
        this.cache = cache;
    }

    @Override
    public DataKey getOrCreateKey(String subjectType, UUID subjectId) {
        subjectTypeById.put(subjectId, subjectType);
        String subjectKey = subjectKey(subjectType, subjectId);
        int currentVersion = currentVersionBySubject.getOrDefault(subjectKey, 0);

        if (currentVersion == 0) {
            // First-time: generate a new key
            byte[] raw = generateRawKey();
            int version = 1;
            String versionedKey = versionedKey(subjectKey, version);
            keysBySubjectAndVersion.put(versionedKey, new KeyRecord(raw, version, SubjectKeyState.ACTIVE));
            currentVersionBySubject.put(subjectKey, version);
            DataKey dk = new DataKey(raw, version, SubjectKeyState.ACTIVE);
            cache.put(subjectType, subjectId, version, dk);
            return dk;
        }

        KeyRecord record = keysBySubjectAndVersion.get(versionedKey(subjectKey, currentVersion));
        DataKey dk = new DataKey(record.keyMaterial(), record.version(), record.state());
        cache.put(subjectType, subjectId, currentVersion, dk);
        return dk;
    }

    @Override
    public DataKey resolveKey(String subjectType, UUID subjectId, int keyVersion) {
        subjectTypeById.put(subjectId, subjectType);
        resolveCallCount.incrementAndGet();

        DataKey cached = cache.get(subjectType, subjectId, keyVersion);
        if (cached != null) return cached;

        String versionedKey = versionedKey(subjectKey(subjectType, subjectId), keyVersion);
        KeyRecord record = keysBySubjectAndVersion.get(versionedKey);
        if (record == null) {
            throw new IllegalArgumentException(
                    "No key found for subject " + subjectId + " version " + keyVersion);
        }
        DataKey dk = new DataKey(record.keyMaterial(), record.version(), record.state());
        cache.put(subjectType, subjectId, keyVersion, dk);
        return dk;
    }

    @Override
    public DataKey resolveKeyById(UUID subjectId, int keyVersion) {
        String subjectType = subjectTypeById.get(subjectId);
        if (subjectType != null) {
            return resolveKey(subjectType, subjectId, keyVersion);
        }
        // Scan for the subjectId across all known subject types
        resolveCallCount.incrementAndGet();
        String suffix = ":" + subjectId + ":" + keyVersion;
        for (Map.Entry<String, KeyRecord> entry : keysBySubjectAndVersion.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                KeyRecord record = entry.getValue();
                return new DataKey(record.keyMaterial(), record.version(), record.state());
            }
        }
        // Subject not yet in our map — check if all versions for this subjectId are DESTROYED
        String destroyedSuffix = ":" + subjectId + ":";
        boolean anyDestroyed = keysBySubjectAndVersion.entrySet().stream()
                .anyMatch(e -> e.getKey().contains(destroyedSuffix)
                               && e.getValue().state() == SubjectKeyState.DESTROYED);
        if (anyDestroyed) {
            return new DataKey(new byte[32], keyVersion, SubjectKeyState.DESTROYED);
        }
        throw new IllegalArgumentException(
                "No key found for subjectId " + subjectId + " version " + keyVersion);
    }

    @Override
    public int rotate(String subjectType, UUID subjectId) {
        subjectTypeById.put(subjectId, subjectType);
        String subjectKey = subjectKey(subjectType, subjectId);
        int currentVersion = currentVersionBySubject.getOrDefault(subjectKey, 0);

        if (currentVersion > 0) {
            // Mark current as ROTATED
            String currentVKey = versionedKey(subjectKey, currentVersion);
            KeyRecord current = keysBySubjectAndVersion.get(currentVKey);
            if (current != null && current.state() == SubjectKeyState.ACTIVE) {
                keysBySubjectAndVersion.put(currentVKey, current.withState(SubjectKeyState.ROTATED));
            }
        }

        int newVersion = currentVersion + 1;
        byte[] raw = generateRawKey();
        String newVKey = versionedKey(subjectKey, newVersion);
        keysBySubjectAndVersion.put(newVKey, new KeyRecord(raw, newVersion, SubjectKeyState.ACTIVE));
        currentVersionBySubject.put(subjectKey, newVersion);

        cache.evict(subjectType, subjectId);
        return newVersion;
    }

    @Override
    public void destroy(String subjectType, UUID subjectId) {
        subjectTypeById.put(subjectId, subjectType);
        String subjectKey = subjectKey(subjectType, subjectId);
        String prefix = subjectKey + ":";

        keysBySubjectAndVersion.replaceAll((k, v) -> {
            if (k.startsWith(prefix)) return v.withState(SubjectKeyState.DESTROYED);
            return v;
        });
        cache.evict(subjectType, subjectId);
    }

    @Override
    public SubjectKeyState getState(String subjectType, UUID subjectId) {
        String subjectKey = subjectKey(subjectType, subjectId);
        int currentVersion = currentVersionBySubject.getOrDefault(subjectKey, 0);
        if (currentVersion == 0) return SubjectKeyState.ACTIVE; // no key yet
        KeyRecord record = keysBySubjectAndVersion.get(versionedKey(subjectKey, currentVersion));
        return record == null ? SubjectKeyState.ACTIVE : record.state();
    }

    @Override
    public int currentVersion(String subjectType, UUID subjectId) {
        return currentVersionBySubject.getOrDefault(subjectKey(subjectType, subjectId), 0);
    }

    @Override
    public void evictCache(String subjectType, UUID subjectId) {
        cache.evict(subjectType, subjectId);
    }

    /** Returns the total number of cache-miss key resolve calls (for test assertions). */
    public int getResolveCallCount() {
        return resolveCallCount.get();
    }

    public SubjectKeyCache getCache() {
        return cache;
    }

    private static byte[] generateRawKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return raw;
    }

    private static String subjectKey(String subjectType, UUID subjectId) {
        return subjectType + ":" + subjectId;
    }

    private static String versionedKey(String subjectKey, int version) {
        return subjectKey + ":" + version;
    }
}
