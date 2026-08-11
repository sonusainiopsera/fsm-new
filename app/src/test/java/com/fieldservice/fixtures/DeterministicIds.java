package com.fieldservice.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic identifier and clock provider for test fixtures.
 *
 * <p>A fixed {@link Clock} and a monotonic UUIDv7 counter ensure that two builds of
 * the same scenario, starting from the same {@link #resetSequence()} call, produce
 * byte-identical identifiers, timestamps, and derived SLA deadlines.
 *
 * <p>Usage pattern:
 * <pre>
 *   DeterministicIds.resetSequence();
 *   UUID id1 = DeterministicIds.nextId(); // always the same value
 *   UUID id2 = DeterministicIds.nextId(); // always one step ahead
 * </pre>
 *
 * <p>Thread safety: {@link #nextId()} and {@link #resetSequence()} are thread-safe via
 * {@link AtomicLong}. Tests that need isolation should call {@link #resetSequence()} in
 * a {@code @BeforeEach} method.
 */
public final class DeterministicIds {

    /** Fixed instant all fixture builders use for timestamps and deadline derivation. */
    public static final Instant EPOCH = Instant.parse("2025-01-15T10:00:00Z");

    /**
     * Fixed clock at {@link #EPOCH} in UTC.
     * Inject into services under test to make deadline computation reproducible.
     */
    public static final Clock CLOCK = Clock.fixed(EPOCH, ZoneOffset.UTC);

    private static final long EPOCH_MS = EPOCH.toEpochMilli(); // 1736935200000L
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private DeterministicIds() {}

    /**
     * Returns the next deterministic UUIDv7.
     *
     * <p>The 48-bit timestamp portion is always {@link #EPOCH_MS}; the 12-bit sequence
     * in the MSB and 62-bit counter in the LSB together form a monotonically increasing,
     * time-ordered, reproducible identifier that satisfies RFC 9562 structural requirements.
     */
    public static UUID nextId() {
        long seq = SEQUENCE.getAndIncrement();
        // UUIDv7 structure (RFC 9562 §5.7):
        //   MSB bits 0-47:  unix_ts_ms
        //   MSB bits 48-51: version = 0b0111
        //   MSB bits 52-63: rand_a (monotonic seq[0..11])
        //   LSB bits 0-1:   variant = 0b10
        //   LSB bits 2-63:  rand_b  (deterministic counter)
        long msb = (EPOCH_MS << 16) | 0x7000L | (seq & 0x0FFFL);
        long lsb = 0x8000000000000000L | (seq & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }

    /**
     * Resets the counter to zero.
     * Call in {@code @BeforeEach} to ensure each test's fixture build starts from the
     * same sequence position, guaranteeing identical UUIDs across independent runs.
     */
    public static void resetSequence() {
        SEQUENCE.set(0);
    }

    /** Returns the current counter value without incrementing. Useful for assertions. */
    public static long currentSequence() {
        return SEQUENCE.get();
    }
}
