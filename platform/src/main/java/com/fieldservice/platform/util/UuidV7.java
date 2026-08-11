package com.fieldservice.platform.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC-9562 UUIDv7 generator.
 *
 * <p>Layout (128 bits):
 * <ul>
 *   <li>Bits 0–47:   48-bit Unix millisecond timestamp (time-ordered B-tree locality)</li>
 *   <li>Bits 48–51:  version = 0x7</li>
 *   <li>Bits 52–63:  12-bit monotonic sub-millisecond sequence (counter, not random)</li>
 *   <li>Bits 64–65:  RFC 4122 variant = 0b10</li>
 *   <li>Bits 66–127: 62 bits cryptographically random</li>
 * </ul>
 *
 * <p>Within a single millisecond the sequence counter ensures monotonic order.
 * When the counter overflows 12 bits the generator advances the timestamp by one
 * millisecond so ordering is preserved under burst load.
 *
 * <p>All public methods are thread-safe.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Guarded by class monitor (synchronized on UuidV7.class)
    private static long lastMs = -1L;
    private static int  seq    = 0;

    private UuidV7() {}

    /**
     * Generates a new UUIDv7.
     *
     * @return a time-ordered, RFC-9562-compliant UUIDv7
     */
    public static synchronized UUID generate() {
        long ms = System.currentTimeMillis();

        if (ms > lastMs) {
            lastMs = ms;
            seq = 0;
        } else {
            // Same millisecond or clock went backwards — advance monotonically
            if (ms < lastMs) {
                ms = lastMs;
            }
            seq++;
            if (seq > 0xFFF) {
                // Sequence overflow: bump to next logical millisecond
                lastMs++;
                ms = lastMs;
                seq = 0;
            }
        }

        // Most significant 64 bits:
        //   [63..16] unix_ts_ms (48 bits)
        //   [15..12] version = 0x7
        //   [11..0 ] rand_a = monotonic sequence (12 bits)
        long msb = (ms << 16) | 0x7000L | (seq & 0xFFFL);

        // Least significant 64 bits:
        //   [63..62] variant = 0b10
        //   [61..0 ] random (62 bits)
        long rand = RANDOM.nextLong();
        long lsb  = (rand & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }

    /**
     * Extracts the Unix millisecond timestamp embedded in a UUIDv7.
     *
     * @param uuid a UUIDv7 (behaviour is undefined for other UUID versions)
     * @return milliseconds since the Unix epoch
     */
    public static long extractTimestamp(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
