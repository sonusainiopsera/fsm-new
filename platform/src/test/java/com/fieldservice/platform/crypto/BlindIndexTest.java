package com.fieldservice.platform.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BlindIndex} (WO-193, AC-12).
 */
@DisplayName("BlindIndex unit tests")
class BlindIndexTest {

    @BeforeAll
    static void configure() {
        BlindIndex.configure(new byte[32]); // 32 zero bytes — deterministic for tests
    }

    @Test
    @DisplayName("null input returns null")
    void nullInput_returnsNull() {
        assertThat(BlindIndex.compute(null)).isNull();
    }

    @Test
    @DisplayName("determinism: same input always produces same HMAC")
    void determinism_sameInputSameOutput() {
        String a = BlindIndex.compute("test@example.com");
        String b = BlindIndex.compute("test@example.com");
        assertThat(a).isEqualTo(b).hasSize(64); // 32 bytes hex = 64 chars
    }

    @Test
    @DisplayName("different inputs produce different HMAC (collision check)")
    void differentInputs_differentHmac() {
        String a = BlindIndex.compute("alice@example.com");
        String b = BlindIndex.compute("bob@example.com");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("output is 64 lowercase hex characters (HMAC-SHA-256)")
    void outputFormat_64HexChars() {
        String result = BlindIndex.compute("value");
        assertThat(result).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("empty string computes a HMAC (not null)")
    void emptyString_computesHmac() {
        assertThat(BlindIndex.compute("")).isNotNull().hasSize(64);
    }

    @Test
    @DisplayName("index key must be exactly 32 bytes")
    void configure_rejectsWrongKeyLength() {
        assertThatThrownBy(() -> BlindIndex.configure(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("blind index is distinct from data encryption — uses HMAC not AES")
    void usesHmacNotAes() {
        // Simply verify the output is deterministic — if it were using AES-GCM it would
        // produce different output each call (random IV). HMAC is deterministic.
        assertThat(BlindIndex.compute("same")).isEqualTo(BlindIndex.compute("same"));
    }
}
