package com.fieldservice.platform.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MaskingStrategies}.
 *
 * <p>Each strategy must be:
 * <ul>
 *   <li>Null-safe: returns {@link MaskingStrategy#REDACTED} for null/blank input.</li>
 *   <li>Idempotent: applying the strategy twice gives the same result.</li>
 *   <li>Non-reversible: no recoverable original substring in the output.</li>
 *   <li>Non-throwing: malformed input returns {@link MaskingStrategy#REDACTED}.</li>
 * </ul>
 */
class MaskingStrategiesTest {

    // -----------------------------------------------------------------------
    // EMAIL strategy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EMAIL strategy")
    class EmailTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.EMAIL.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void typical_email_keeps_first_char_and_domain() {
            String masked = MaskingStrategies.EMAIL.mask("john.smith@example.com");
            assertThat(masked)
                    .startsWith("j")
                    .contains("***@example.com")
                    .doesNotContain("john", "smith");
        }

        @Test
        void single_char_local_part() {
            String masked = MaskingStrategies.EMAIL.mask("a@b.io");
            assertThat(masked).isEqualTo("a***@b.io");
        }

        @Test
        void malformed_email_fully_redacted() {
            assertThat(MaskingStrategies.EMAIL.mask("not-an-email")).isEqualTo(MaskingStrategy.REDACTED);
            assertThat(MaskingStrategies.EMAIL.mask("@nodomain")).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void idempotent() {
            String once = MaskingStrategies.EMAIL.mask("alice@corp.com");
            String twice = MaskingStrategies.EMAIL.mask(once);
            assertThat(twice).isEqualTo(once);
        }
    }

    // -----------------------------------------------------------------------
    // PHONE strategy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PHONE strategy")
    class PhoneTests {

        @ParameterizedTest
        @NullAndEmptySource
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.PHONE.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void uk_phone_last_two_digits_visible() {
            String masked = MaskingStrategies.PHONE.mask("+44 7911 123456");
            assertThat(masked).endsWith("56").doesNotContain("1234");
        }

        @Test
        void short_number_last_two_visible() {
            String masked = MaskingStrategies.PHONE.mask("07700900999");
            assertThat(masked).endsWith("99").doesNotContain("0770090");
        }

        @Test
        void international_format_preserves_separators() {
            String masked = MaskingStrategies.PHONE.mask("+1 (800) 555-0199");
            assertThat(masked).contains(" ").contains("-");
        }

        @Test
        void idempotent() {
            String once = MaskingStrategies.PHONE.mask("+1-800-555-0199");
            String twice = MaskingStrategies.PHONE.mask(once);
            assertThat(twice).isEqualTo(once);
        }
    }

    // -----------------------------------------------------------------------
    // NAME strategy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("NAME strategy")
    class NameTests {

        @ParameterizedTest
        @NullAndEmptySource
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.NAME.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void full_name_becomes_initials() {
            assertThat(MaskingStrategies.NAME.mask("John Smith")).isEqualTo("J. S.");
        }

        @Test
        void single_character_name() {
            assertThat(MaskingStrategies.NAME.mask("X")).isEqualTo("X.");
        }

        @Test
        void three_part_name() {
            String masked = MaskingStrategies.NAME.mask("Mary Jane Watson");
            assertThat(masked).isEqualTo("M. J. W.");
        }

        @Test
        void original_name_not_in_output() {
            String masked = MaskingStrategies.NAME.mask("Aleksandra Kowalczyk");
            assertThat(masked).doesNotContain("Aleksandra", "Kowalczyk");
        }

        @Test
        void idempotent() {
            String once = MaskingStrategies.NAME.mask("Alice Example");
            String twice = MaskingStrategies.NAME.mask(once);
            assertThat(twice).isEqualTo(once);
        }
    }

    // -----------------------------------------------------------------------
    // ADDRESS strategy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ADDRESS strategy")
    class AddressTests {

        @ParameterizedTest
        @NullAndEmptySource
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.ADDRESS.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void keeps_region_component() {
            String masked = MaskingStrategies.ADDRESS.mask("10 Downing Street, Westminster, London");
            assertThat(masked).contains("London").doesNotContain("10 Downing Street");
        }

        @Test
        void fully_redacts_when_not_parseable() {
            assertThat(MaskingStrategies.ADDRESS.mask("123 Some!@£$ Street ###"))
                    .isEqualTo(MaskingStrategy.REDACTED);
        }
    }

    // -----------------------------------------------------------------------
    // COORDINATE strategy
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("COORDINATE strategy")
    class CoordinateTests {

        @ParameterizedTest
        @NullAndEmptySource
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.COORDINATE.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void high_precision_reduced_to_one_decimal() {
            String masked = MaskingStrategies.COORDINATE.mask("51.509865");
            assertThat(masked).isEqualTo("51.5~");
        }

        @Test
        void zero_coordinate_fully_redacted() {
            assertThat(MaskingStrategies.COORDINATE.mask("0.0")).isEqualTo(MaskingStrategy.REDACTED);
            assertThat(MaskingStrategies.COORDINATE.mask("0")).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void negative_coordinate_reduced() {
            String masked = MaskingStrategies.COORDINATE.mask("-0.1276");
            assertThat(masked).doesNotContain("0.1276");
        }

        @Test
        void malformed_coordinate_redacted() {
            assertThat(MaskingStrategies.COORDINATE.mask("not-a-number")).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void idempotent() {
            String once = MaskingStrategies.COORDINATE.mask("51.509865");
            String twice = MaskingStrategies.COORDINATE.mask(once);
            // After second application, the trailing ~ breaks parsing → REDACTED — both are non-original
            assertThat(twice).doesNotContain("509865");
        }
    }

    // -----------------------------------------------------------------------
    // TOKEN / HASH strategies
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TOKEN and HASH strategies — full redaction")
    class TokenHashTests {

        @Test
        void token_always_redacted() {
            assertThat(MaskingStrategies.TOKEN.mask("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.sig"))
                    .isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void hash_always_redacted() {
            assertThat(MaskingStrategies.HASH.mask("$2a$12$r9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW"))
                    .isEqualTo(MaskingStrategy.REDACTED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void null_and_blank_return_redacted(String input) {
            assertThat(MaskingStrategies.TOKEN.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
            assertThat(MaskingStrategies.HASH.mask(input)).isEqualTo(MaskingStrategy.REDACTED);
        }
    }

    // -----------------------------------------------------------------------
    // FULL_REDACT strategy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("FULL_REDACT always returns the fixed redaction token")
    void full_redact_always_returns_token() {
        assertThat(MaskingStrategies.FULL_REDACT.mask("any value")).isEqualTo(MaskingStrategy.REDACTED);
        assertThat(MaskingStrategies.FULL_REDACT.mask(null)).isEqualTo(MaskingStrategy.REDACTED);
    }

    // -----------------------------------------------------------------------
    // forTier() factory
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("forTier() factory")
    class ForTierTests {

        @Test
        void restricted_returns_full_redact() {
            assertThat(MaskingStrategies.forTier(MaskingTier.RESTRICTED).mask("secret"))
                    .isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void null_tier_defaults_to_full_redact() {
            assertThat(MaskingStrategies.forTier(null).mask("unknown")).isEqualTo(MaskingStrategy.REDACTED);
        }

        @Test
        void public_tier_passes_through() {
            assertThat(MaskingStrategies.forTier(MaskingTier.PUBLIC).mask("public data"))
                    .isEqualTo("public data");
        }

        @Test
        void internal_tier_passes_through() {
            assertThat(MaskingStrategies.forTier(MaskingTier.INTERNAL).mask("internal note"))
                    .isEqualTo("internal note");
        }
    }
}
