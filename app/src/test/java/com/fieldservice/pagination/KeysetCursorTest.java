package com.fieldservice.pagination;

import com.fieldservice.platform.pagination.InvalidCursorException;
import com.fieldservice.platform.pagination.KeysetCursor;
import com.fieldservice.platform.pagination.SortField;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for KeysetCursor encode/decode, tamper detection, and cross-sort-order replay.
 */
class KeysetCursorTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-32b";
    private static final Instant CREATED_AT = Instant.ofEpochMilli(1_718_000_000_000L);
    private static final UUID ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String FINGERPRINT = "createdAt:DESC,id:ASC";

    // -------------------------------------------------------------------------
    // Round-trip encode/decode
    // -------------------------------------------------------------------------

    @Test
    void encode_then_decode_round_trip() {
        KeysetCursor original = new KeysetCursor(FINGERPRINT, CREATED_AT, ID);
        String token = original.encode(SECRET);

        KeysetCursor decoded = KeysetCursor.decode(token, FINGERPRINT, SECRET);

        assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(decoded.id()).isEqualTo(ID);
        assertThat(decoded.sortFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void encoded_cursor_is_url_safe() {
        String token = new KeysetCursor(FINGERPRINT, CREATED_AT, ID).encode(SECRET);
        // base64url must not contain '+', '/', or '='
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    void cursor_contains_exactly_one_dot_separator() {
        String token = new KeysetCursor(FINGERPRINT, CREATED_AT, ID).encode(SECRET);
        long dots = token.chars().filter(c -> c == '.').count();
        assertThat(dots).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Tamper detection
    // -------------------------------------------------------------------------

    @Test
    void flipping_one_bit_in_payload_is_detected() {
        String token = new KeysetCursor(FINGERPRINT, CREATED_AT, ID).encode(SECRET);
        int dot = token.lastIndexOf('.');
        String payload = token.substring(0, dot);
        String sig     = token.substring(dot + 1);

        // Flip last char of payload
        char last = payload.charAt(payload.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        String tampered = payload.substring(0, payload.length() - 1) + flipped + "." + sig;

        assertThatThrownBy(() -> KeysetCursor.decode(tampered, FINGERPRINT, SECRET))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void replacing_signature_is_detected() {
        String token = new KeysetCursor(FINGERPRINT, CREATED_AT, ID).encode(SECRET);
        int dot = token.lastIndexOf('.');
        String payload = token.substring(0, dot);

        String tampered = payload + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertThatThrownBy(() -> KeysetCursor.decode(tampered, FINGERPRINT, SECRET))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void wrong_secret_causes_decode_failure() {
        String token = new KeysetCursor(FINGERPRINT, CREATED_AT, ID).encode(SECRET);

        assertThatThrownBy(() -> KeysetCursor.decode(token, FINGERPRINT, "wrong-secret-key!!!!!!!!!!!!!!!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    // -------------------------------------------------------------------------
    // Cross-sort-order replay detection
    // -------------------------------------------------------------------------

    @Test
    void cursor_issued_for_one_sort_rejected_when_replayed_against_another() {
        String fingerprint1 = "createdAt:DESC,id:ASC";
        String fingerprint2 = "title:ASC,id:ASC";

        String token = new KeysetCursor(fingerprint1, CREATED_AT, ID).encode(SECRET);

        assertThatThrownBy(() -> KeysetCursor.decode(token, fingerprint2, SECRET))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("different sort order");
    }

    // -------------------------------------------------------------------------
    // Malformed input
    // -------------------------------------------------------------------------

    @Test
    void blank_cursor_throws_invalid_cursor_exception() {
        assertThatThrownBy(() -> KeysetCursor.decode("", FINGERPRINT, SECRET))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void cursor_without_dot_separator_is_rejected() {
        assertThatThrownBy(() -> KeysetCursor.decode("nodothere", FINGERPRINT, SECRET))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void random_garbage_string_is_rejected() {
        assertThatThrownBy(() -> KeysetCursor.decode("garbage!@#$%", FINGERPRINT, SECRET))
                .isInstanceOf(InvalidCursorException.class);
    }

    // -------------------------------------------------------------------------
    // Fingerprint computation
    // -------------------------------------------------------------------------

    @Test
    void fingerprint_for_standard_work_order_sort_is_correct() {
        List<SortField> sorts = List.of(
                new SortField("createdAt", Sort.Direction.DESC),
                new SortField("id", Sort.Direction.ASC)
        );
        assertThat(KeysetCursor.computeFingerprint(sorts)).isEqualTo("createdAt:DESC,id:ASC");
    }

    @Test
    void fingerprint_for_empty_sort_list_is_empty_string() {
        assertThat(KeysetCursor.computeFingerprint(List.of())).isEmpty();
    }
}
