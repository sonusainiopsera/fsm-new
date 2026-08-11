package com.fieldservice.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UuidV7} — verifies RFC 9562 structural properties
 * and monotonic ordering guarantees.
 */
class UuidV7Test {

    @Test
    @DisplayName("Generated UUID has version 7")
    void version_isAlways7() {
        for (int i = 0; i < 1000; i++) {
            UUID uuid = UuidV7.generate();
            assertThat(UuidV7.isVersion7(uuid))
                    .as("UUID %s must have version 7", uuid)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Generated UUID has RFC 9562 variant (10xx)")
    void variant_isRfc9562() {
        for (int i = 0; i < 1000; i++) {
            UUID uuid = UuidV7.generate();
            assertThat(UuidV7.isRfc9562Variant(uuid))
                    .as("UUID %s must have RFC 9562 variant", uuid)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Embedded timestamp is within 1 second of wall clock")
    void embeddedTimestamp_closenessToWallClock() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();

        long embedded = UuidV7.extractTimestampMs(uuid);
        assertThat(embedded).isBetween(before, after);
    }

    @Test
    @DisplayName("100 sequentially generated UUIDs are strictly monotonically increasing")
    void monotonicallyIncreasing_sequentialGeneration() {
        int count = 100;
        List<UUID> uuids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            uuids.add(UuidV7.generate());
        }

        for (int i = 1; i < uuids.size(); i++) {
            UUID prev = uuids.get(i - 1);
            UUID curr = uuids.get(i);
            assertThat(curr.compareTo(prev))
                    .as("UUID[%d] %s must be > UUID[%d] %s", i, curr, i - 1, prev)
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("100,000 generated UUIDs are all unique")
    void uniqueness_100kUuids() {
        int count = 100_000;
        Set<UUID> seen = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = UuidV7.generate();
            assertThat(seen.add(uuid))
                    .as("UUID %s was generated twice — collision at iteration %d", uuid, i)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("isVersion7 returns false for a v4 UUID")
    void isVersion7_returnsFalseForV4() {
        UUID v4 = UUID.randomUUID();
        assertThat(UuidV7.isVersion7(v4)).isFalse();
    }

    @RepeatedTest(10)
    @DisplayName("Repeated generation returns different UUIDs")
    void repeatedCalls_returnDifferentUuids() {
        UUID a = UuidV7.generate();
        UUID b = UuidV7.generate();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("extractTimestampMs returns the timestamp embedded in the MSB")
    void extractTimestampMs_roundtrip() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();

        long extracted = UuidV7.extractTimestampMs(uuid);
        assertThat(extracted)
                .isGreaterThanOrEqualTo(before)
                .isLessThanOrEqualTo(after);
    }
}
