package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BlindIndex}.
 */
class BlindIndexTest {

    private BlindIndex blindIndex;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        blindIndex = new BlindIndex(new SecretKeySpec(key, "HmacSHA256"));
    }

    @Test
    @DisplayName("Null input returns null")
    void null_input_returns_null() {
        assertThat(blindIndex.compute(null)).isNull();
    }

    @Test
    @DisplayName("Blank input returns null")
    void blank_input_returns_null() {
        assertThat(blindIndex.compute("   ")).isNull();
    }

    @Test
    @DisplayName("Deterministic: same plaintext always produces same index value")
    void deterministic() {
        String idx1 = blindIndex.compute("alice@example.com");
        String idx2 = blindIndex.compute("alice@example.com");
        assertThat(idx1).isEqualTo(idx2);
    }

    @Test
    @DisplayName("Case-insensitive: upper and lower case produce same index")
    void case_insensitive() {
        assertThat(blindIndex.compute("Alice@Example.COM"))
                .isEqualTo(blindIndex.compute("alice@example.com"));
    }

    @Test
    @DisplayName("Whitespace-trimming: leading/trailing spaces normalised before hashing")
    void whitespace_trimming() {
        assertThat(blindIndex.compute("  alice@example.com  "))
                .isEqualTo(blindIndex.compute("alice@example.com"));
    }

    @Test
    @DisplayName("Different plaintexts produce different index values (no trivial collision)")
    void different_plaintexts_produce_different_indexes() {
        String idx1 = blindIndex.compute("alice@example.com");
        String idx2 = blindIndex.compute("bob@example.com");
        assertThat(idx1).isNotEqualTo(idx2);
    }

    @Test
    @DisplayName("Output is 64-char lowercase hex (HMAC-SHA-256)")
    void output_is_64_char_hex() {
        String idx = blindIndex.compute("test@example.com");
        assertThat(idx).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Different keys produce different indexes for same plaintext")
    void different_key_different_index() {
        byte[] key2 = new byte[32];
        new SecureRandom().nextBytes(key2);
        BlindIndex other = new BlindIndex(new SecretKeySpec(key2, "HmacSHA256"));

        String idx1 = blindIndex.compute("test@example.com");
        String idx2 = other.compute("test@example.com");
        assertThat(idx1).isNotEqualTo(idx2);
    }

    @Test
    @DisplayName("No collision in sample of 100 distinct emails")
    void no_collision_in_sample() {
        Set<String> indexes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            indexes.add(blindIndex.compute("user" + i + "@example.com"));
        }
        assertThat(indexes).hasSize(100);
    }
}
