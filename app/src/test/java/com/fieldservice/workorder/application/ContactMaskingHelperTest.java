package com.fieldservice.workorder.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContactMaskingHelper} (WO-154 AC-9).
 */
class ContactMaskingHelperTest {

    @Test
    void maskPhone_returns_last_four_digits() {
        assertThat(ContactMaskingHelper.maskPhone("+44 7700 900001")).isEqualTo("****0001");
    }

    @Test
    void maskPhone_strips_non_digits_before_masking() {
        assertThat(ContactMaskingHelper.maskPhone("(020) 1234-5678")).isEqualTo("****5678");
    }

    @Test
    void maskPhone_exactly_four_digits() {
        assertThat(ContactMaskingHelper.maskPhone("1234")).isEqualTo("****1234");
    }

    @Test
    void maskPhone_fewer_than_four_digits_returns_four_stars() {
        assertThat(ContactMaskingHelper.maskPhone("123")).isEqualTo("****");
    }

    @Test
    void maskPhone_null_returns_null() {
        assertThat(ContactMaskingHelper.maskPhone(null)).isNull();
    }

    @Test
    void maskPhone_blank_returns_null() {
        assertThat(ContactMaskingHelper.maskPhone("   ")).isNull();
    }

    @Test
    void maskPhone_no_digits_returns_null() {
        assertThat(ContactMaskingHelper.maskPhone("n/a")).isNull();
    }
}
