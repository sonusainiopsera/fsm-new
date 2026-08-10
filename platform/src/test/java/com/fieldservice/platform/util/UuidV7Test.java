package com.fieldservice.platform.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void version_nibble_is_7() {
        UUID uuid = UuidV7.generate();
        int version = (int) ((uuid.getMostSignificantBits() >> 12) & 0xF);
        assertThat(version).isEqualTo(7);
    }

    @Test
    void variant_bits_are_0b10() {
        UUID uuid = UuidV7.generate();
        int variant = (int) ((uuid.getLeastSignificantBits() >>> 62) & 0x3);
        assertThat(variant).isEqualTo(0b10);
    }

    @Test
    void timestamp_matches_current_millisecond_bucket() {
        long before = System.currentTimeMillis();
        UUID uuid = UuidV7.generate();
        long after = System.currentTimeMillis();

        long embedded = uuid.getMostSignificantBits() >>> 16;
        assertThat(embedded).isBetween(before, after);
    }

    @Test
    void ids_generated_across_millisecond_boundaries_are_monotonically_increasing() {
        // Generate IDs in distinct milliseconds and verify string ordering.
        // Relies on the fact that the 48-bit timestamp occupies the most significant
        // bits of the UUID, so lexicographic order equals temporal order.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t = System.currentTimeMillis();
            while (System.currentTimeMillis() == t) { /* spin until next ms */ }
            ids.add(UuidV7.generate().toString());
        }
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
        }
    }

    @Test
    void one_hundred_thousand_ids_are_unique() {
        int count = 100_000;
        Set<UUID> seen = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UuidV7.generate();
            assertThat(seen.add(id))
                    .as("Duplicate UUID generated: %s", id)
                    .isTrue();
        }
    }

    @Test
    void ids_from_different_milliseconds_preserve_ordering() {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() == t0) { /* spin for tick */ }

        UUID early = UuidV7.generate();

        long t1 = System.currentTimeMillis();
        while (System.currentTimeMillis() == t1) { /* spin for tick */ }

        UUID late = UuidV7.generate();

        // Timestamps embedded in UUIDv7 must be strictly increasing across ms buckets.
        long tsEarly = early.getMostSignificantBits() >>> 16;
        long tsLate  = late.getMostSignificantBits()  >>> 16;
        assertThat(tsLate).isGreaterThan(tsEarly);
    }
}
