package com.fieldservice.platform.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for KeysetCursor encode/decode round-trips and tamper-detection.
 * Covers AC-7 (cursor tamper-evidence) and AC-9 (unit tests).
 */
@DisplayName("KeysetCursor unit tests")
class KeysetCursorTest {

    private static final byte[] SECRET = "test-secret-key-must-be-long-enough".getBytes(StandardCharsets.UTF_8);
    private static final Instant FIXED_TIME = Instant.parse("2024-06-01T12:00:00Z");
    private static final UUID FIXED_ID = UUID.fromString("018f5e3a-1234-7000-abcd-ef0123456789");

    // ── Round-trip (AC-9) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Encode then decode returns the original cursor values")
    void encode_decode_round_trip() {
        KeysetCursor original = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC");
        String token = original.encode(SECRET);
        KeysetCursor decoded = KeysetCursor.decode(token, SECRET, "DESC");

        assertThat(decoded.createdAt()).isEqualTo(FIXED_TIME);
        assertThat(decoded.id()).isEqualTo(FIXED_ID);
        assertThat(decoded.direction()).isEqualTo("DESC");
    }

    @Test
    @DisplayName("Round-trip preserves Instant precision to milliseconds")
    void round_trip_preserves_millis() {
        Instant ts = Instant.ofEpochMilli(1717243200123L);
        KeysetCursor c = new KeysetCursor(ts, FIXED_ID, "ASC");
        KeysetCursor decoded = KeysetCursor.decode(c.encode(SECRET), SECRET, "ASC");
        assertThat(decoded.createdAt().toEpochMilli()).isEqualTo(ts.toEpochMilli());
    }

    @Test
    @DisplayName("Token is opaque (looks like base64url with a single dot separator)")
    void token_is_opaque_base64url() {
        String token = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        assertThat(token).doesNotContain("{", "}", ":", "<", ">");
        assertThat(token.split("\\.")).hasSize(2);
    }

    // ── Tamper detection (AC-7) ───────────────────────────────────────────────

    @Test
    @DisplayName("Altered payload is rejected with InvalidCursorException")
    void altered_payload_is_rejected() {
        String token = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        // Flip a character in the payload portion
        String tampered = "A" + token.substring(1);
        assertThatThrownBy(() -> KeysetCursor.decode(tampered, SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("Wrong secret rejects the cursor")
    void wrong_secret_is_rejected() {
        String token = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        byte[] wrongSecret = "different-secret".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> KeysetCursor.decode(token, wrongSecret, "DESC"))
            .isInstanceOf(InvalidCursorException.class)
            .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("Cross-sort-order replay (DESC cursor vs ASC request) is rejected")
    void cross_sort_order_replay_is_rejected() {
        String descToken = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        assertThatThrownBy(() -> KeysetCursor.decode(descToken, SECRET, "ASC"))
            .isInstanceOf(InvalidCursorException.class)
            .hasMessageContaining("sort-order fingerprint");
    }

    @Test
    @DisplayName("Malformed token (no dot separator) is rejected")
    void malformed_token_no_dot_is_rejected() {
        assertThatThrownBy(() -> KeysetCursor.decode("notadottoken", SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("Blank cursor is rejected")
    void blank_cursor_is_rejected() {
        assertThatThrownBy(() -> KeysetCursor.decode("", SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> KeysetCursor.decode("   ", SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("Null cursor is rejected")
    void null_cursor_is_rejected() {
        assertThatThrownBy(() -> KeysetCursor.decode(null, SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("Truncated signature is rejected")
    void truncated_signature_is_rejected() {
        String token = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        // Keep payload, truncate signature
        String[] parts = token.split("\\.", 2);
        String truncated = parts[0] + "." + parts[1].substring(0, 4);
        assertThatThrownBy(() -> KeysetCursor.decode(truncated, SECRET, "DESC"))
            .isInstanceOf(InvalidCursorException.class);
    }

    // ── Link generation boundary cases (AC-9) ────────────────────────────────

    @Test
    @DisplayName("Two different cursors produce different tokens")
    void different_cursors_produce_different_tokens() {
        String t1 = new KeysetCursor(FIXED_TIME, FIXED_ID, "DESC").encode(SECRET);
        String t2 = new KeysetCursor(FIXED_TIME.plusSeconds(1), FIXED_ID, "DESC").encode(SECRET);
        assertThat(t1).isNotEqualTo(t2);
    }
}
