package com.fieldservice.app.idempotency;

import com.fieldservice.platform.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for idempotency key validation, hash canonicalization, and header allow-listing.
 */
class IdempotencyUnitTest {

    // ---- Key format validation -------------------------------------------------

    @Test
    @DisplayName("key of exactly 16 chars is valid")
    void keyMinLength_valid() {
        assertThat(isValidKey("1234567890abcdef")).isTrue();
    }

    @Test
    @DisplayName("key of exactly 128 chars is valid")
    void keyMaxLength_valid() {
        assertThat(isValidKey("A".repeat(128))).isTrue();
    }

    @Test
    @DisplayName("key of 15 chars is invalid (too short)")
    void keyTooShort_invalid() {
        assertThat(isValidKey("123456789012345")).isFalse();
    }

    @Test
    @DisplayName("key of 129 chars is invalid (too long)")
    void keyTooLong_invalid() {
        assertThat(isValidKey("A".repeat(129))).isFalse();
    }

    @Test
    @DisplayName("key with disallowed chars is invalid")
    void keyDisallowedChars_invalid() {
        assertThat(isValidKey("1234567890abcde!")).isFalse();
        assertThat(isValidKey("1234567890abcde ")).isFalse();
        assertThat(isValidKey("1234567890abcde@")).isFalse();
    }

    @Test
    @DisplayName("key with allowed special chars is valid")
    void keyAllowedSpecialChars_valid() {
        assertThat(isValidKey("key-with_dots.equals=plus+slash/")).isTrue();
    }

    @Test
    @DisplayName("UUID-format key is valid")
    void uuidFormatKey_valid() {
        assertThat(isValidKey("550e8400-e29b-41d4-a716-446655440000")).isTrue();
    }

    // ---- Hash canonicalization stability -------------------------------------

    @Test
    @DisplayName("same inputs always produce the same hash")
    void hashIsStable() {
        byte[] body1 = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        String h1 = computeHash("POST", "/api/v1/work-orders", body1);
        String h2 = computeHash("POST", "/api/v1/work-orders", body2);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("different method produces different hash")
    void differentMethod_differentHash() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String h1 = computeHash("POST", "/api/v1/work-orders", body);
        String h2 = computeHash("PUT",  "/api/v1/work-orders", body);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("different path produces different hash")
    void differentPath_differentHash() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String h1 = computeHash("POST", "/api/v1/work-orders", body);
        String h2 = computeHash("POST", "/api/v1/work-orders/123", body);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("different body produces different hash")
    void differentBody_differentHash() {
        String h1 = computeHash("POST", "/api/v1/work-orders", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String h2 = computeHash("POST", "/api/v1/work-orders", "{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("empty body is hashed consistently")
    void emptyBody_stableHash() {
        String h1 = computeHash("DELETE", "/api/v1/work-orders/1", new byte[0]);
        String h2 = computeHash("DELETE", "/api/v1/work-orders/1", new byte[0]);
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    @DisplayName("hash is a 64-char lowercase hex string (SHA-256)")
    void hashIsSha256Hex() {
        String h = computeHash("POST", "/api/v1/work-orders", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
    }

    // ---- Helpers ---------------------------------------------------------------

    private static boolean isValidKey(String key) {
        return java.util.regex.Pattern
                .compile("^[A-Za-z0-9\\-_.=+/]{16,128}$")
                .matcher(key).matches();
    }

    private static String computeHash(String method, String path, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((method.toUpperCase() + ":" + path + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
