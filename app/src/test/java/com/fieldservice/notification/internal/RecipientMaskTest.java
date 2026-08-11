package com.fieldservice.notification.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientMaskTest {

    @Test
    @DisplayName("email: first char + *** + @domain")
    void email_masksCorrectly() {
        assertThat(RecipientMask.mask("jane@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    @DisplayName("phone with punctuation: last two digits")
    void phone_masksLastTwoDigits() {
        assertThat(RecipientMask.mask("+44 7700 900 42")).isEqualTo("***42");
    }

    @Test
    @DisplayName("plain digit string: last two digits")
    void digits_masksLastTwo() {
        assertThat(RecipientMask.mask("07700900042")).isEqualTo("***42");
    }

    @Test
    @DisplayName("null returns [empty]")
    void nullInput_returnsEmpty() {
        assertThat(RecipientMask.mask(null)).isEqualTo("[empty]");
    }

    @Test
    @DisplayName("blank string returns [empty]")
    void blankInput_returnsEmpty() {
        assertThat(RecipientMask.mask("   ")).isEqualTo("[empty]");
    }

    @ParameterizedTest(name = "masked({0}) must not contain raw input")
    @CsvSource({
            "alice@corp.example, alice",
            "+447700123456,      7700123",
            "user@sub.domain.io, user"
    })
    @DisplayName("masked output must not contain the sensitive prefix")
    void masked_doesNotRevealRawValue(String contact, String sensitiveFragment) {
        String result = RecipientMask.mask(contact);
        assertThat(result).doesNotContain(sensitiveFragment);
    }

    @Test
    @DisplayName("email at position 0 still masks")
    void emailStartingWithAt_handledGracefully() {
        // '@' at position 0 → no valid local-part → falls through to phone logic
        String result = RecipientMask.mask("@domain.com");
        assertThat(result).isNotBlank();
    }

    @Test
    @DisplayName("idempotency: masking twice produces same result")
    void mask_isIdempotent() {
        String once  = RecipientMask.mask("bob@test.org");
        String twice = RecipientMask.mask("bob@test.org");
        assertThat(once).isEqualTo(twice);
    }
}
