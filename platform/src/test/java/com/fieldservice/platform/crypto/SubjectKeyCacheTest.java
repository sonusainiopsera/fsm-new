package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubjectKeyCache}.
 */
class SubjectKeyCacheTest {

    private static final SubjectRef SUBJECT_A =
            SubjectRef.of("TECHNICIAN", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final SubjectRef SUBJECT_B =
            SubjectRef.of("TECHNICIAN", UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private SubjectKeyCache cache;

    @BeforeEach
    void setUp() {
        cache = new SubjectKeyCache();
    }

    @Test
    @DisplayName("get returns empty when nothing cached")
    void get_empty_when_nothing_cached() {
        assertThat(cache.get(SUBJECT_A, 1)).isEmpty();
    }

    @Test
    @DisplayName("put then get returns the cached spec")
    void put_then_get() throws Exception {
        SubjectKeySpec spec = spec(1);
        cache.put(SUBJECT_A, spec);
        Optional<SubjectKeySpec> result = cache.get(SUBJECT_A, 1);
        assertThat(result).isPresent();
        assertThat(result.get().keyVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Different subjects are cached independently")
    void independent_subjects() throws Exception {
        SubjectKeySpec specA = spec(1);
        SubjectKeySpec specB = spec(1);
        cache.put(SUBJECT_A, specA);
        cache.put(SUBJECT_B, specB);

        assertThat(cache.get(SUBJECT_A, 1)).isPresent();
        assertThat(cache.get(SUBJECT_B, 1)).isPresent();
        assertThat(cache.get(SUBJECT_A, 1).get().secretKey())
                .isNotEqualTo(cache.get(SUBJECT_B, 1).get().secretKey());
    }

    @Test
    @DisplayName("evict removes all versions for the given subject")
    void evict_removes_all_versions() throws Exception {
        cache.put(SUBJECT_A, spec(1));
        cache.put(SUBJECT_A, spec(2));
        cache.put(SUBJECT_B, spec(1));

        cache.evict(SUBJECT_A);

        assertThat(cache.get(SUBJECT_A, 1)).isEmpty();
        assertThat(cache.get(SUBJECT_A, 2)).isEmpty();
        assertThat(cache.get(SUBJECT_B, 1)).isPresent();
    }

    @Test
    @DisplayName("evict is idempotent")
    void evict_idempotent() {
        cache.evict(SUBJECT_A);
        cache.evict(SUBJECT_A);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("TTL expiry: expired entry returns empty (short TTL cache)")
    void ttl_expiry() throws Exception {
        SubjectKeyCache shortTtlCache = new SubjectKeyCache(512, 0);
        SubjectKeySpec spec = spec(1);
        shortTtlCache.put(SUBJECT_A, spec);
        Thread.sleep(10);
        assertThat(shortTtlCache.get(SUBJECT_A, 1)).isEmpty();
    }

    @Test
    @DisplayName("Capacity bound: old entry evicted when cache is full")
    void capacity_bound() throws Exception {
        SubjectKeyCache smallCache = new SubjectKeyCache(2, 3600);
        SubjectRef s1 = SubjectRef.of("T", UUID.randomUUID());
        SubjectRef s2 = SubjectRef.of("T", UUID.randomUUID());
        SubjectRef s3 = SubjectRef.of("T", UUID.randomUUID());

        smallCache.put(s1, spec(1));
        smallCache.put(s2, spec(1));
        smallCache.put(s3, spec(1));

        assertThat(smallCache.size()).isLessThanOrEqualTo(2);
    }

    // ---- helpers -----------------------------------------------------------

    private static SubjectKeySpec spec(int version) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();
        return new SubjectKeySpec(key, version);
    }
}
