package com.fieldservice.platform.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC-9562 UUIDv7 identifiers.
 *
 * <p>Structure (128 bits):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           unix_ts_ms                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          unix_ts_ms           |  ver  |       rand_a          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                        rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           rand_b                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 * Bits 0–47: 48-bit Unix milliseconds.<br>
 * Bits 48–51: version nibble {@code 0x7}.<br>
 * Bits 52–63: 12-bit random rand_a.<br>
 * Bits 64–65: variant bits {@code 0b10}.<br>
 * Bits 66–127: 62-bit random rand_b.<br>
 */
public final class UuidV7 {

    private static final SecureRandom RNG = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long nowMs = System.currentTimeMillis();
        long rndHi = RNG.nextLong();
        long rndLo = RNG.nextLong();

        // Most significant 64 bits:
        //   bits 63–16: unix_ts_ms (48 bits)
        //   bits 15–12: version = 0x7
        //   bits 11–0:  rand_a (12 bits)
        long msb = (nowMs << 16)                   // 48-bit timestamp in top 48 bits
                 | (0x7000L)                        // version nibble = 7
                 | (rndHi & 0x0FFFL);              // rand_a: 12 random bits

        // Least significant 64 bits:
        //   bits 63–62: variant = 0b10
        //   bits 61–0:  rand_b (62 bits)
        long lsb = (rndLo & 0x3FFF_FFFF_FFFF_FFFFL) // clear top 2 bits
                 | 0x8000_0000_0000_0000L;            // set variant 0b10

        return new UUID(msb, lsb);
    }
}
