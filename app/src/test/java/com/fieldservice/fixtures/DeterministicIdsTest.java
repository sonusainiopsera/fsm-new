package com.fieldservice.fixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeterministicIds unit tests")
class DeterministicIdsTest {

    @BeforeEach
    void resetSequence() {
        DeterministicIds.resetSequence();
    }

    @Test
    @DisplayName("Two sequences starting from reset produce identical UUIDs")
    void determinism_sameSequenceTwice_identicalUuids() {
        UUID first1 = DeterministicIds.nextId();
        UUID first2 = DeterministicIds.nextId();

        DeterministicIds.resetSequence();

        UUID second1 = DeterministicIds.nextId();
        UUID second2 = DeterministicIds.nextId();

        assertThat(first1).isEqualTo(second1);
        assertThat(first2).isEqualTo(second2);
    }

    @Test
    @DisplayName("Generated UUIDs are strictly monotonically ordered")
    void nextId_monotonicallyIncreasing() {
        UUID a = DeterministicIds.nextId();
        UUID b = DeterministicIds.nextId();
        UUID c = DeterministicIds.nextId();

        // String comparison works for time-ordered UUIDv7
        assertThat(a.toString()).isLessThan(b.toString());
        assertThat(b.toString()).isLessThan(c.toString());
    }

    @Test
    @DisplayName("Generated UUIDs have correct UUIDv7 version nibble (7)")
    void nextId_versionNibbleIsSeven() {
        UUID id = DeterministicIds.nextId();
        // Version nibble is bits 48-51 of UUID string (character 14 in 8-4-4-4-12 format)
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    @DisplayName("Generated UUIDs have correct RFC 4122 variant (2)")
    void nextId_variantIsRfc4122() {
        UUID id = DeterministicIds.nextId();
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("Clock is fixed at EPOCH")
    void clock_isFixedAtEpoch() {
        assertThat(DeterministicIds.CLOCK.instant()).isEqualTo(DeterministicIds.EPOCH);
        // Second read returns same instant (clock does not advance)
        assertThat(DeterministicIds.CLOCK.instant()).isEqualTo(DeterministicIds.EPOCH);
    }

    @Test
    @DisplayName("resetSequence resets counter to zero")
    void resetSequence_resetsCounterToZero() {
        DeterministicIds.nextId();
        DeterministicIds.nextId();
        assertThat(DeterministicIds.currentSequence()).isEqualTo(2);

        DeterministicIds.resetSequence();
        assertThat(DeterministicIds.currentSequence()).isEqualTo(0);
    }

    @Test
    @DisplayName("All UUIDs in a sequence are unique")
    void nextId_hundredIds_allUnique() {
        var ids = new java.util.HashSet<UUID>();
        for (int i = 0; i < 100; i++) {
            ids.add(DeterministicIds.nextId());
        }
        assertThat(ids).hasSize(100);
    }
}
