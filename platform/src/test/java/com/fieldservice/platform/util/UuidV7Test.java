package com.fieldservice.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UuidV7}: ordering, uniqueness at volume, and correct bit encoding.
 */
class UuidV7Test {

    private static final int VOLUME = 100_000;

    @Test
    @DisplayName("version nibble is 7")
    void version_is_7() {
        UUID uuid = UuidV7.generate();
        // version is bits 76-79 of UUID = bits 12-15 of mostSigBits (long)
        int version = (int) ((uuid.getMostSignificantBits() >> 12) & 0xF);
        assertThat(version).isEqualTo(7);
    }

    @Test
    @DisplayName("variant bits are 10xxxxxx (RFC 4122 / RFC 9562)")
    void variant_bits_are_rfc4122() {
        UUID uuid = UuidV7.generate();
        // variant is the top 2 bits of leastSigBits
        long top2 = uuid.getLeastSignificantBits() >>> 62;
        // 0b10 = 2
        assertThat(top2).isEqualTo(2L);
    }

    @Test
    @DisplayName("timestamp extracted from UUIDv7 is within 5 seconds of now")
    void timestamp_is_recent() {
        long before = System.currentTimeMillis();
        UUID uuid   = UuidV7.generate();
        long after  = System.currentTimeMillis();

        long ts = UuidV7.extractTimestamp(uuid);
        assertThat(ts).isGreaterThanOrEqualTo(before);
        assertThat(ts).isLessThanOrEqualTo(after + 5_000);
    }

    @Test
    @DisplayName("100k generated IDs are all unique")
    void uniqueness_at_volume() {
        Set<UUID> ids = new HashSet<>(VOLUME);
        for (int i = 0; i < VOLUME; i++) {
            ids.add(UuidV7.generate());
        }
        assertThat(ids).hasSize(VOLUME);
    }

    @Test
    @DisplayName("100k generated IDs are monotonically non-decreasing in lexicographic order")
    void monotonic_ordering_at_volume() {
        UUID[] ids = new UUID[VOLUME];
        for (int i = 0; i < VOLUME; i++) {
            ids[i] = UuidV7.generate();
        }
        for (int i = 1; i < VOLUME; i++) {
            // UUID compareTo uses unsigned comparison on each 64-bit half
            assertThat(ids[i].compareTo(ids[i - 1]))
                    .as("ID[%d] must be >= ID[%d]", i, i - 1)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @RepeatedTest(5)
    @DisplayName("timestamps across millisecond boundaries remain ordered")
    void ordering_across_millisecond_boundary() throws InterruptedException {
        UUID before = UuidV7.generate();
        Thread.sleep(2);
        UUID after = UuidV7.generate();
        assertThat(after.compareTo(before)).isGreaterThan(0);
        assertThat(UuidV7.extractTimestamp(after))
                .isGreaterThan(UuidV7.extractTimestamp(before));
    }
}
