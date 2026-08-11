package com.fieldservice.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed {@link Clock} and deterministic UUID supplier for test fixtures.
 *
 * <p>The fixed instant is 2025-01-15T09:00:00Z.  UUIDs follow the RFC-9562
 * UUIDv7 layout with fixed timestamp bits, a monotonically incrementing 12-bit
 * sequence, and counter-derived variant bits so two equal builds produce
 * bit-identical UUIDs.
 *
 * <p>Call {@link #reset()} at the top of every fixture scenario so the counter
 * restarts from zero and IDs are reproducible across multiple test runs.
 */
public final class DeterministicIds {

    /** Fixed reference instant used by every builder. */
    public static final Instant FIXED_INSTANT = Instant.parse("2025-01-15T09:00:00Z");

    /** Clock that always returns {@link #FIXED_INSTANT}. */
    public static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final long       FIXED_MS = FIXED_INSTANT.toEpochMilli();
    private static final AtomicLong COUNTER  = new AtomicLong(0);

    private DeterministicIds() {}

    /** Resets the sequence counter to zero. */
    public static void reset() {
        COUNTER.set(0);
    }

    /**
     * Returns the next deterministic UUID (UUIDv7 layout, RFC-9562).
     *
     * <ul>
     *   <li>Bits 0–47:   fixed unix_ts_ms = FIXED_MS</li>
     *   <li>Bits 48–51:  version = 0x7</li>
     *   <li>Bits 52–63:  monotonic 12-bit counter</li>
     *   <li>Bits 64–65:  RFC-4122 variant = 0b10</li>
     *   <li>Bits 66–127: counter-derived deterministic bits</li>
     * </ul>
     */
    public static UUID next() {
        long n   = COUNTER.getAndIncrement();
        long seq = n & 0xFFFL;
        long msb = (FIXED_MS << 16) | 0x7000L | seq;
        long lsb = deriveRandom(n);
        return new UUID(msb, lsb);
    }

    private static long deriveRandom(long seed) {
        long h = seed * 6364136223846793005L + 1442695040888963407L;
        return (h & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    }
}
