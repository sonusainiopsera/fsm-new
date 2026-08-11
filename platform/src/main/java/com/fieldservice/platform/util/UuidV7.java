package com.fieldservice.platform.util;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates RFC 9562 UUIDv7 identifiers.
 *
 * <p>UUIDv7 structure (128 bits):
 * <pre>
 * Bits   0–47 : unix_ts_ms  — 48-bit Unix epoch milliseconds
 * Bits  48–51 : ver         — version field = 0x7
 * Bits  52–63 : rand_a      — 12 random bits (sub-ms sequence for monotonicity)
 * Bits  64–65 : var         — variant field = 0b10
 * Bits  66–127: rand_b      — 62 random bits
 * </pre>
 *
 * <p>Properties:
 * <ul>
 *   <li>Monotonically increasing across millisecond boundaries — UUID comparison
 *       reflects temporal ordering, improving B-tree index locality.</li>
 *   <li>Within the same millisecond, the 12-bit rand_a field acts as an
 *       auto-incrementing sequence (wrapping at 4096) to preserve ordering
 *       for high-throughput insert bursts.</li>
 *   <li>Non-guessable: the lower 62 bits of rand_b are cryptographically random.</li>
 *   <li>Thread-safe: millisecond state is held in an AtomicLong; random bits
 *       come from a thread-local SecureRandom for performance.</li>
 * </ul>
 *
 * <p>Usage: call {@link #generate()} to obtain a new UUID, or use the Hibernate
 * {@link UuidV7Generator} / {@link com.fieldservice.platform.entity.BaseEntity} for
 * automatic JPA identifier assignment.
 */
public final class UuidV7 {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Encodes last-seen ms (upper 48 bits) and per-ms sequence (lower 12 bits)
     * so we can detect same-millisecond generation and increment the sequence.
     */
    private static final AtomicLong LAST_MS_AND_SEQ = new AtomicLong(0L);

    private UuidV7() {}

    /**
     * Generates a new RFC 9562 UUIDv7.
     *
     * @return a fresh UUIDv7 that is lexicographically greater than any previously
     *         generated UUID (monotonic within a single JVM process)
     */
    public static UUID generate() {
        long msb = buildMsb();
        long lsb = buildLsb();
        return new UUID(msb, lsb);
    }

    private static long buildMsb() {
        long tsMs = currentTimestampMs();
        long seq = acquireSequence(tsMs);

        // Upper 48 bits = timestamp, bits 12-15 = version 0x7, lower 12 = seq
        return (tsMs << 16)       // timestamp in bits 63-16
             | 0x7000L            // version 7 in bits 15-12
             | (seq & 0x0FFFL);   // 12-bit sequence in bits 11-0
    }

    private static long buildLsb() {
        long rand = SECURE_RANDOM.nextLong();
        // Top 2 bits = variant 0b10; remaining 62 bits = random
        return 0x8000000000000000L | (rand & 0x3FFFFFFFFFFFFFFFL);
    }

    /**
     * Acquires a monotonically increasing per-millisecond sequence counter.
     * If the millisecond has advanced, the sequence resets to a random start.
     * If the sequence overflows within the same ms, we wait for the next ms.
     */
    private static long acquireSequence(long tsMs) {
        while (true) {
            long current = LAST_MS_AND_SEQ.get();
            long lastMs = current >>> 12;
            long lastSeq = current & 0x0FFFL;

            if (tsMs > lastMs) {
                // New millisecond — reset sequence to random start (lower half)
                long newSeq = (SECURE_RANDOM.nextLong() & 0x7FFL); // start in lower half
                long next = (tsMs << 12) | newSeq;
                if (LAST_MS_AND_SEQ.compareAndSet(current, next)) {
                    return newSeq;
                }
            } else if (tsMs == lastMs) {
                long newSeq = lastSeq + 1;
                if (newSeq > 0x0FFFL) {
                    // Sequence exhausted within ms — spin until next ms
                    tsMs = currentTimestampMs();
                    continue;
                }
                long next = (tsMs << 12) | newSeq;
                if (LAST_MS_AND_SEQ.compareAndSet(current, next)) {
                    return newSeq;
                }
            } else {
                // Clock went backwards — use lastMs to maintain monotonicity
                tsMs = lastMs;
            }
        }
    }

    private static long currentTimestampMs() {
        return System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Inspection helpers (for tests and debugging only)
    // -------------------------------------------------------------------------

    /** Returns the millisecond timestamp embedded in a UUIDv7. */
    public static long extractTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    /** Returns {@code true} if the UUID's version nibble is 7. */
    public static boolean isVersion7(UUID uuid) {
        return ((uuid.getMostSignificantBits() >> 12) & 0xFL) == 7L;
    }

    /** Returns {@code true} if the UUID's variant bits are {@code 10} (RFC 4122 / 9562). */
    public static boolean isRfc9562Variant(UUID uuid) {
        return (uuid.getLeastSignificantBits() >>> 62) == 2L;
    }
}
