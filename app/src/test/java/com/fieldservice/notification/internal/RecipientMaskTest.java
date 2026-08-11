package com.fieldservice.notification.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecipientMask} — fast, no Spring context (WO-195).
 */
@DisplayName("RecipientMask unit tests")
class RecipientMaskTest {

    @Test
    @DisplayName("null contact returns ***")
    void nullContact_returns_redacted() {
        assertThat(RecipientMask.mask(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("blank contact returns ***")
    void blankContact_returns_redacted() {
        assertThat(RecipientMask.mask("   ")).isEqualTo("***");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "john.doe@example.com,  j***@example.com",
        "a@b.com,               a***@b.com",
        "dispatcher@test.org,   d***@test.org"
    })
    @DisplayName("Email addresses are masked with first char + ***@domain")
    void emailMasking(String contact, String expected) {
        assertThat(RecipientMask.mask(contact)).isEqualTo(expected.strip());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "+447911123456, **56",
        "07700900042,   **42",
        "12345,         **45"
    })
    @DisplayName("Phone numbers are masked to last 2 digits")
    void phoneMasking(String contact, String expected) {
        assertThat(RecipientMask.mask(contact)).isEqualTo(expected.strip());
    }

    @Test
    @DisplayName("Masking is stable — same input always produces same output")
    void masking_isStable() {
        String email = "user@example.com";
        assertThat(RecipientMask.mask(email)).isEqualTo(RecipientMask.mask(email));
    }

    @Test
    @DisplayName("Raw email is not present in masked output")
    void maskedEmail_doesNotContainLocalPart() {
        String masked = RecipientMask.mask("john.doe@example.com");
        assertThat(masked).doesNotContain("john.doe");
        assertThat(masked).doesNotContain("john");
        assertThat(masked).contains("@example.com");
    }

    @Test
    @DisplayName("Generic contact uses first 2 chars + ***")
    void genericContact_usesPrefix() {
        String masked = RecipientMask.mask("FCM_TOKEN_XYZ");
        assertThat(masked).startsWith("FC");
        assertThat(masked).endsWith("***");
    }
}
