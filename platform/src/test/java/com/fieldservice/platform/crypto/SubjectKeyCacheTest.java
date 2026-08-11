package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubjectKeyCache} (WO-193, AC-12).
 */
@DisplayName("SubjectKeyCache unit tests")
class SubjectKeyCacheTest {

    private static final UUID SUBJECT_ID = UUID.fromString("bb000000-0000-7000-8000-000000000001");
    private static final String SUBJECT_TYPE = "TECHNICIAN";

    private DataKey makeKey(int version) {
        return new DataKey(new byte[32], version, SubjectKeyState.ACTIVE);
    }

    @Test
    @DisplayName("put/get round-trip returns same key")
    void putGet_returnsCachedKey() {
        SubjectKeyCache cache = new SubjectKeyCache();
        DataKey key = makeKey(1);
        cache.put(SUBJECT_TYPE, SUBJECT_ID, 1, key);
        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 1)).isNotNull()
                .extracting(DataKey::version).isEqualTo(1);
    }

    @Test
    @DisplayName("get for non-existent entry returns null")
    void get_missingEntry_returnsNull() {
        SubjectKeyCache cache = new SubjectKeyCache();
        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 99)).isNull();
    }

    @Test
    @DisplayName("evict removes all versions for subject")
    void evict_removesAllVersionsForSubject() {
        SubjectKeyCache cache = new SubjectKeyCache();
        cache.put(SUBJECT_TYPE, SUBJECT_ID, 1, makeKey(1));
        cache.put(SUBJECT_TYPE, SUBJECT_ID, 2, makeKey(2));
        cache.put("OTHER", SUBJECT_ID, 1, makeKey(1)); // different subject type

        cache.evict(SUBJECT_TYPE, SUBJECT_ID);

        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 1)).isNull();
        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 2)).isNull();
        // Other subject type untouched
        assertThat(cache.get("OTHER", SUBJECT_ID, 1)).isNotNull();
    }

    @Test
    @DisplayName("cache respects max size (LRU eviction)")
    void maxSize_evictsLruEntry() {
        SubjectKeyCache cache = new SubjectKeyCache(3, Long.MAX_VALUE);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        UUID s4 = UUID.randomUUID();

        cache.put(SUBJECT_TYPE, s1, 1, makeKey(1));
        cache.put(SUBJECT_TYPE, s2, 1, makeKey(1));
        cache.put(SUBJECT_TYPE, s3, 1, makeKey(1));
        // s1 is now LRU; adding s4 should evict s1
        cache.put(SUBJECT_TYPE, s4, 1, makeKey(1));

        assertThat(cache.size()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("TTL expiry evicts entry on access")
    void ttlExpiry_evictsOnAccess() throws InterruptedException {
        SubjectKeyCache cache = new SubjectKeyCache(512, 50); // 50 ms TTL
        cache.put(SUBJECT_TYPE, SUBJECT_ID, 1, makeKey(1));
        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 1)).isNotNull();

        Thread.sleep(100); // wait for TTL to expire
        assertThat(cache.get(SUBJECT_TYPE, SUBJECT_ID, 1)).isNull();
    }
}
