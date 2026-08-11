package com.fieldservice.platform.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHashTest {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    // ── Hash canonicalization ─────────────────────────────────────────────

    @Test
    void hash_is_stable_for_same_inputs() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String h1 = IdempotencyKeyFilter.computeHash("POST", "/test/path", body);
        String h2 = IdempotencyKeyFilter.computeHash("POST", "/test/path", body);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hash_is_64_hex_chars() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = IdempotencyKeyFilter.computeHash("POST", "/api/v1/resource", body);
        assertThat(hash).matches(HEX64);
    }

    @Test
    void hash_differs_for_different_methods() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String h1 = IdempotencyKeyFilter.computeHash("POST", "/resource", body);
        String h2 = IdempotencyKeyFilter.computeHash("PUT", "/resource", body);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hash_differs_for_different_paths() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String h1 = IdempotencyKeyFilter.computeHash("POST", "/resource/1", body);
        String h2 = IdempotencyKeyFilter.computeHash("POST", "/resource/2", body);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hash_differs_for_different_bodies() {
        String h1 = IdempotencyKeyFilter.computeHash("POST", "/resource", "body-a".getBytes(StandardCharsets.UTF_8));
        String h2 = IdempotencyKeyFilter.computeHash("POST", "/resource", "body-b".getBytes(StandardCharsets.UTF_8));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hash_handles_empty_body() {
        String hash = IdempotencyKeyFilter.computeHash("DELETE", "/resource/1", new byte[0]);
        assertThat(hash).matches(HEX64);
    }

    @Test
    void hash_method_is_case_insensitive() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String h1 = IdempotencyKeyFilter.computeHash("post", "/resource", body);
        String h2 = IdempotencyKeyFilter.computeHash("POST", "/resource", body);
        assertThat(h1).isEqualTo(h2);
    }

    // ── Key validation ────────────────────────────────────────────────────

    @Test
    void key_exactly_16_chars_is_valid() {
        assertThat(isValid("a".repeat(16))).isTrue();
    }

    @Test
    void key_exactly_128_chars_is_valid() {
        assertThat(isValid("a".repeat(128))).isTrue();
    }

    @Test
    void key_15_chars_is_invalid() {
        assertThat(isValid("a".repeat(15))).isFalse();
    }

    @Test
    void key_129_chars_is_invalid() {
        assertThat(isValid("a".repeat(129))).isFalse();
    }

    @Test
    void key_with_allowed_special_chars_is_valid() {
        assertThat(isValid("abcXYZ-_+./0123456789")).isTrue();
    }

    @Test
    void key_with_space_is_invalid() {
        assertThat(isValid("key with spaces     ")).isFalse();
    }

    @Test
    void key_with_at_sign_is_invalid() {
        assertThat(isValid("test@key-12345678901")).isFalse();
    }

    @Test
    void empty_key_is_invalid() {
        assertThat(isValid("")).isFalse();
    }

    // ── Header allow-listing ──────────────────────────────────────────────

    @Test
    void replay_headers_contains_expected_entries() {
        assertThat(IdempotencyKeyFilter.REPLAY_HEADERS)
                .contains("content-type", "location", "etag", "x-trace-id");
    }

    @Test
    void replay_headers_excludes_sensitive_headers() {
        assertThat(IdempotencyKeyFilter.REPLAY_HEADERS)
                .doesNotContain("set-cookie", "authorization", "x-auth-token");
    }

    // ── State transitions ─────────────────────────────────────────────────

    @Test
    void claim_result_new_has_record_id() {
        var result = IdempotencyClaimResult.newKey(java.util.UUID.randomUUID());
        assertThat(result.type()).isEqualTo(IdempotencyClaimResult.ClaimType.NEW);
        assertThat(result.recordId()).isNotNull();
        assertThat(result.record()).isNull();
    }

    @Test
    void claim_result_replayed_has_record() {
        var record = new IdempotencyRecord(
                java.util.UUID.randomUUID(), "key", "user", "endpoint",
                "hash", 201, "{\"count\":1}", null,
                IdempotencyKeyState.COMPLETED,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600));
        var result = IdempotencyClaimResult.replayed(record.id(), record);
        assertThat(result.type()).isEqualTo(IdempotencyClaimResult.ClaimType.REPLAYED);
        assertThat(result.record()).isSameAs(record);
    }

    @Test
    void claim_result_hash_conflict_has_no_record() {
        var result = IdempotencyClaimResult.hashConflict();
        assertThat(result.type()).isEqualTo(IdempotencyClaimResult.ClaimType.CONFLICT_HASH);
        assertThat(result.recordId()).isNull();
        assertThat(result.record()).isNull();
    }

    @Test
    void claim_result_in_progress_conflict_has_no_record() {
        var result = IdempotencyClaimResult.inProgressConflict();
        assertThat(result.type()).isEqualTo(IdempotencyClaimResult.ClaimType.CONFLICT_IN_PROGRESS);
        assertThat(result.recordId()).isNull();
    }

    private static boolean isValid(String key) {
        return IdempotencyKeyFilter.KEY_PATTERN.matcher(key).matches();
    }
}
