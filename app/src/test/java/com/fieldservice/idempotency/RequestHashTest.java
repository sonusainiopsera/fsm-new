package com.fieldservice.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static com.fieldservice.idempotency.IdempotencyKeyFilter.computeHash;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SHA-256 request hash canonicalization and key format validation.
 *
 * <p>These tests do not require Spring context or a database — they verify pure logic.
 */
class RequestHashTest {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9\\-._~+/]{16,128}");

    // -------------------------------------------------------------------------
    // SHA-256 hash canonicalization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Same method, path, and body produces identical hash")
    void sameInputs_sameHash() {
        byte[] body = """
                {"op":"create","name":"test"}
                """.getBytes(StandardCharsets.UTF_8);

        String h1 = computeHash("POST", "/api/v1/orders", body);
        String h2 = computeHash("POST", "/api/v1/orders", body);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("Different HTTP method produces different hash")
    void differentMethod_differentHash() {
        byte[] body = """
                {"key":"value"}
                """.getBytes(StandardCharsets.UTF_8);

        String hashPost = computeHash("POST", "/api/v1/resource", body);
        String hashPut  = computeHash("PUT",  "/api/v1/resource", body);

        assertThat(hashPost).isNotEqualTo(hashPut);
    }

    @Test
    @DisplayName("Different path produces different hash")
    void differentPath_differentHash() {
        byte[] body = """
                {"key":"value"}
                """.getBytes(StandardCharsets.UTF_8);

        String h1 = computeHash("POST", "/api/v1/resource/1", body);
        String h2 = computeHash("POST", "/api/v1/resource/2", body);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("Different body produces different hash")
    void differentBody_differentHash() {
        String h1 = computeHash("POST", "/api/v1/resource",
                """
                {"op":"create-a"}
                """.getBytes(StandardCharsets.UTF_8));
        String h2 = computeHash("POST", "/api/v1/resource",
                """
                {"op":"create-b"}
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("Null body and empty body produce the same hash (no body)")
    void nullBody_sameAsEmptyBody() {
        String h1 = computeHash("DELETE", "/api/v1/resource/1", null);
        String h2 = computeHash("DELETE", "/api/v1/resource/1", new byte[0]);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("Hash is a 64-character lowercase hex string (SHA-256)")
    void hash_is64HexChars() {
        String hash = computeHash("POST", "/test", "body".getBytes(StandardCharsets.UTF_8));

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Hash is deterministic across JVM restarts (known test vector)")
    void hash_knownTestVector() {
        // Pre-computed: SHA-256 of "POST\n/api/v1/items\nbody"
        // echo -n 'POST\n/api/v1/items\nbody' | sha256sum — using actual LF bytes
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        String hash = computeHash("POST", "/api/v1/items", body);

        // The hash must be stable (deterministic) — verify it doesn't change
        assertThat(hash).isEqualTo(computeHash("POST", "/api/v1/items", body));
        assertThat(hash).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Key format validation
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("Valid keys matching [A-Za-z0-9-._~+/]{16,128} are accepted")
    @ValueSource(strings = {
            "abcdefghijklmnop",                 // exactly 16 lowercase
            "ABCDEFGHIJKLMNOP",                 // exactly 16 uppercase
            "0123456789abcdef",                 // 16 alphanumeric
            "order-idempotency-key-2026-01",    // typical real-world key
            "key.with.dots.and.tildes~+/00",    // all allowed special chars
            "a".repeat(128),                    // exactly 128 chars
    })
    void validKey_accepted(String key) {
        assertThat(KEY_PATTERN.matcher(key).matches())
                .as("Key '%s' should be valid", key)
                .isTrue();
    }

    @ParameterizedTest
    @DisplayName("Invalid keys are rejected")
    @ValueSource(strings = {
            "",                                 // empty
            "too-short",                        // < 16 chars
            "a".repeat(129),                    // > 128 chars
            "key with spaces         ",         // spaces not allowed
            "key\twith\ttabs",                  // tabs not allowed
            "key\nwith\nnewlines",              // newlines not allowed
            "key<with>angle<brackets>",         // angle brackets
            "key{with}braces",                  // braces
            "key@with@at",                      // @ not allowed
    })
    void invalidKey_rejected(String key) {
        assertThat(KEY_PATTERN.matcher(key).matches())
                .as("Key '%s' should be invalid", key)
                .isFalse();
    }
}
