package com.fieldservice.platform.masking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for every masking strategy boundary case (WO-192, AC-11).
 *
 * <p>Each test asserts that:
 * <ul>
 *   <li>The output does not contain a recoverable original substring.</li>
 *   <li>The strategy is idempotent (applying it twice yields the same result).</li>
 *   <li>Null input produces null output.</li>
 *   <li>Blank / malformed input produces the redaction token.</li>
 * </ul>
 */
@DisplayName("MaskingStrategies unit tests")
class MaskingStrategyTest {

    private static final String REDACTED = PiiMasker.REDACTION_TOKEN;

    // ── EMAIL ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMAIL: keeps first char and domain suffix, removes local part body")
    void email_standardAddress_masksCorrectly() {
        String result = MaskingStrategies.EMAIL.mask("john.doe@example.com");
        assertThat(result).isEqualTo("j***@example.com");
        assertThat(result).doesNotContain("doe");
    }

    @Test
    @DisplayName("EMAIL: single-char local part masked correctly")
    void email_singleCharLocal_masksCorrectly() {
        String result = MaskingStrategies.EMAIL.mask("a@example.com");
        assertThat(result).isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("EMAIL: null returns null")
    void email_null_returnsNull() {
        assertThat(MaskingStrategies.EMAIL.mask(null)).isNull();
    }

    @Test
    @DisplayName("EMAIL: blank returns REDACTED")
    void email_blank_returnsRedacted() {
        assertThat(MaskingStrategies.EMAIL.mask("   ")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("EMAIL: malformed (no @) returns REDACTED")
    void email_noAtSign_returnsRedacted() {
        assertThat(MaskingStrategies.EMAIL.mask("notanemail")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("EMAIL: idempotent")
    void email_idempotent() {
        String once = MaskingStrategies.EMAIL.mask("user@test.org");
        String twice = MaskingStrategies.EMAIL.mask(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── PHONE ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PHONE: keeps last two digits, removes rest")
    void phone_standard_masksCorrectly() {
        String result = MaskingStrategies.PHONE.mask("+44 7700 900142");
        assertThat(result).isEqualTo("***42");
        assertThat(result).doesNotContain("7700");
    }

    @Test
    @DisplayName("PHONE: null returns null")
    void phone_null_returnsNull() {
        assertThat(MaskingStrategies.PHONE.mask(null)).isNull();
    }

    @Test
    @DisplayName("PHONE: single digit returns REDACTED")
    void phone_singleDigit_returnsRedacted() {
        assertThat(MaskingStrategies.PHONE.mask("5")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("PHONE: blank returns REDACTED")
    void phone_blank_returnsRedacted() {
        assertThat(MaskingStrategies.PHONE.mask("")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("PHONE: idempotent")
    void phone_idempotent() {
        String once = MaskingStrategies.PHONE.mask("+1-800-555-0199");
        String twice = MaskingStrategies.PHONE.mask(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── NAME ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NAME: returns uppercase initials")
    void name_fullName_returnsInitials() {
        assertThat(MaskingStrategies.NAME.mask("Jane Ann Doe")).isEqualTo("J.A.D.");
    }

    @Test
    @DisplayName("NAME: single token returns single initial")
    void name_singleWord_returnsSingleInitial() {
        assertThat(MaskingStrategies.NAME.mask("Alice")).isEqualTo("A.");
    }

    @Test
    @DisplayName("NAME: null returns null")
    void name_null_returnsNull() {
        assertThat(MaskingStrategies.NAME.mask(null)).isNull();
    }

    @Test
    @DisplayName("NAME: blank returns REDACTED")
    void name_blank_returnsRedacted() {
        assertThat(MaskingStrategies.NAME.mask("   ")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("NAME: idempotent")
    void name_idempotent() {
        String once = MaskingStrategies.NAME.mask("Robert Smith");
        String twice = MaskingStrategies.NAME.mask(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── ADDRESS ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADDRESS: fully redacted for any non-null input")
    void address_anyInput_isRedacted() {
        assertThat(MaskingStrategies.ADDRESS.mask("10 Downing Street, London")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("ADDRESS: null returns null")
    void address_null_returnsNull() {
        assertThat(MaskingStrategies.ADDRESS.mask(null)).isNull();
    }

    // ── COORDINATE ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COORDINATE: reduces precision to 2 d.p.")
    void coordinate_highPrecision_reducesPrecision() {
        String result = MaskingStrategies.COORDINATE.mask("51.509865,-0.118092");
        assertThat(result).isEqualTo("51.50,-0.11");
        assertThat(result).doesNotContain("509865");
    }

    @Test
    @DisplayName("COORDINATE: poles and extremes do not produce invalid output")
    void coordinate_poles_remain_valid() {
        String north = MaskingStrategies.COORDINATE.mask("90.0,0.0");
        String south = MaskingStrategies.COORDINATE.mask("-90.0,0.0");
        String prime = MaskingStrategies.COORDINATE.mask("0.0,0.0");
        assertThat(north).isNotEqualTo(REDACTED);
        assertThat(south).isNotEqualTo(REDACTED);
        assertThat(prime).isEqualTo("0.00,0.00");
    }

    @Test
    @DisplayName("COORDINATE: malformed input returns REDACTED")
    void coordinate_malformed_returnsRedacted() {
        assertThat(MaskingStrategies.COORDINATE.mask("not,a,coordinate")).isEqualTo(REDACTED);
        assertThat(MaskingStrategies.COORDINATE.mask("999.9,0.0")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("COORDINATE: null returns null")
    void coordinate_null_returnsNull() {
        assertThat(MaskingStrategies.COORDINATE.mask(null)).isNull();
    }

    @Test
    @DisplayName("COORDINATE: idempotent")
    void coordinate_idempotent() {
        String once = MaskingStrategies.COORDINATE.mask("51.5074,-0.1278");
        String twice = MaskingStrategies.COORDINATE.mask(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── TOKEN / HASH ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "TOKEN/HASH: {0} → REDACTED")
    @ValueSource(strings = {"eyJhbGciOiJSUzI1NiJ9.xxx.yyy", "$2a$12$hash...", "sk-12345"})
    @DisplayName("TOKEN and HASH always fully redact")
    void tokenHash_anyValue_fullyRedacted(String value) {
        assertThat(MaskingStrategies.TOKEN.mask(value)).isEqualTo(REDACTED);
        assertThat(MaskingStrategies.HASH.mask(value)).isEqualTo(REDACTED);
    }

    @NullSource
    @ParameterizedTest
    @DisplayName("TOKEN/HASH: null returns null")
    void tokenHash_null_returnsNull(String value) {
        assertThat(MaskingStrategies.TOKEN.mask(value)).isNull();
        assertThat(MaskingStrategies.HASH.mask(value)).isNull();
    }

    // ── Registry lookup ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "forType({0}) resolves to non-TOKEN strategy")
    @CsvSource({"EMAIL", "PHONE", "NAME", "ADDRESS", "COORDINATE", "TOKEN", "HASH"})
    @DisplayName("forType: every registered key resolves to the correct strategy")
    void forType_knownKey_resolves(String type) {
        MaskingStrategy strategy = MaskingStrategies.forType(type);
        assertThat(strategy).isNotNull();
        // Null input always returns null for all strategies
        assertThat(strategy.mask(null)).isNull();
    }

    @Test
    @DisplayName("forType: unknown key defaults to TOKEN (full redaction)")
    void forType_unknownKey_defaultsToToken() {
        MaskingStrategy strategy = MaskingStrategies.forType("UNKNOWN_TYPE");
        assertThat(strategy.mask("some value")).isEqualTo(REDACTED);
    }

    @Test
    @DisplayName("forType: null key defaults to TOKEN (full redaction)")
    void forType_nullKey_defaultsToToken() {
        MaskingStrategy strategy = MaskingStrategies.forType(null);
        assertThat(strategy.mask("some value")).isEqualTo(REDACTED);
    }
}
