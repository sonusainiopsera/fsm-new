package com.fieldservice.app.pagination;

import com.fieldservice.platform.api.exception.InvalidCursorException;
import com.fieldservice.platform.pagination.KeysetCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KeysetCursor}: encode/decode round-trips, tamper detection,
 * and cross-sort rejection.
 */
class KeysetCursorTest {

    private static final String HMAC_KEY = "test-hmac-secret-key-for-unit-tests";
    private static final String FP       = "createdAt:desc,id:asc";

    private KeysetCursor cursor;

    @BeforeEach
    void setUp() {
        cursor = new KeysetCursor(HMAC_KEY);
    }

    @Test
    @DisplayName("encode then decode round-trips all fields")
    void encodeDecodeRoundTrip() {
        Instant ts  = Instant.ofEpochMilli(1_700_000_000_000L);
        UUID    id  = UUID.fromString("018c2d3e-4f56-7890-abcd-ef1234567890");
        String  token = cursor.encode(ts, id, FP);
        KeysetCursor.Payload p = cursor.decode(token, FP);
        assertThat(p.lastCreatedAt()).isEqualTo(ts);
        assertThat(p.lastId()).isEqualTo(id);
        assertThat(p.sortFingerprint()).isEqualTo(FP);
    }

    @Test
    @DisplayName("different HMAC key cannot decode the token")
    void differentKey_failsHmacVerification() {
        Instant ts = Instant.now();
        UUID    id = UUID.randomUUID();
        String  token = cursor.encode(ts, id, FP);

        KeysetCursor other = new KeysetCursor("a-completely-different-key");
        assertThatThrownBy(() -> other.decode(token, FP))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("bit-flipped payload → HMAC mismatch → InvalidCursorException")
    void tamperedPayload_failsHmacCheck() {
        Instant ts    = Instant.ofEpochMilli(1_700_000_000_000L);
        UUID    id    = UUID.randomUUID();
        String  token = cursor.encode(ts, id, FP);

        // Flip one character in the payload portion
        String[] parts = token.split("\\.");
        char[] payloadChars = parts[0].toCharArray();
        payloadChars[5] = (payloadChars[5] == 'A') ? 'B' : 'A';
        String tampered = new String(payloadChars) + "." + parts[1];

        assertThatThrownBy(() -> cursor.decode(tampered, FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("tampered signature portion → HMAC mismatch")
    void tamperedSignature_failsHmacCheck() {
        Instant ts    = Instant.now();
        UUID    id    = UUID.randomUUID();
        String  token = cursor.encode(ts, id, FP);

        String[] parts = token.split("\\.");
        char[] sigChars = parts[1].toCharArray();
        sigChars[3] = (sigChars[3] == 'A') ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(sigChars);

        assertThatThrownBy(() -> cursor.decode(tampered, FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("cursor issued for sort A rejects when decoded with sort B")
    void crossSortCursor_rejected() {
        Instant ts     = Instant.now();
        UUID    id     = UUID.randomUUID();
        String  tokenA = cursor.encode(ts, id, "createdAt:asc,id:asc");

        assertThatThrownBy(() -> cursor.decode(tokenA, "createdAt:desc,id:asc"))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("sort order");
    }

    @Test
    @DisplayName("blank cursor → InvalidCursorException")
    void blankCursor_throwsException() {
        assertThatThrownBy(() -> cursor.decode("  ", FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("null cursor → InvalidCursorException")
    void nullCursor_throwsException() {
        assertThatThrownBy(() -> cursor.decode(null, FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("cursor with no dot separator → InvalidCursorException")
    void noDotSeparator_throwsException() {
        assertThatThrownBy(() -> cursor.decode("nodothere", FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("non-base64 payload → InvalidCursorException")
    void invalidBase64_throwsException() {
        assertThatThrownBy(() -> cursor.decode("not!base64.alsoinvalid!!", FP))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("encoded token does not contain dots inside payload or sig portions (base64url)")
    void encodedToken_usesBase64Url() {
        Instant ts    = Instant.now();
        UUID    id    = UUID.randomUUID();
        String  token = cursor.encode(ts, id, FP);
        // Only one dot separator
        assertThat(token.chars().filter(c -> c == '.').count()).isEqualTo(1);
        // No padding chars
        assertThat(token).doesNotContain("=");
    }

    @Test
    @DisplayName("different timestamps produce different tokens")
    void differentTimestamps_differentTokens() {
        UUID    id  = UUID.fromString("018c2d3e-4f56-7890-abcd-ef1234567890");
        String  t1  = cursor.encode(Instant.ofEpochMilli(1_000L), id, FP);
        String  t2  = cursor.encode(Instant.ofEpochMilli(2_000L), id, FP);
        assertThat(t1).isNotEqualTo(t2);
    }
}
