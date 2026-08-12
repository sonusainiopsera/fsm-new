package com.fieldservice.workorder.duplicates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for fault-signature normalisation.
 * Pins the exact rule set so a rule change requires a deliberate test update.
 */
class FaultSignatureNormalizerTest {

    private final FaultSignatureNormalizer normalizer = new FaultSignatureNormalizer();

    @Test
    void normalize_typical_fault_description() {
        String[] tokens = normalizer.normalize("The water pump is leaking at the inlet");
        assertThat(tokens).contains("water", "pump", "leaking", "inlet");
        assertThat(tokens).doesNotContain("the", "is", "at");
    }

    @Test
    void normalize_null_returns_empty() {
        assertThat(normalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalize_blank_returns_empty() {
        assertThat(normalizer.normalize("   ")).isEmpty();
    }

    @Test
    void normalize_only_stop_words_returns_empty() {
        assertThat(normalizer.normalize("the is it and or")).isEmpty();
    }

    @Test
    void normalize_short_tokens_filtered_out() {
        // tokens "ab", "a", "bc" all below MIN_TOKEN_LENGTH=3
        assertThat(normalizer.normalize("ab a bc motor failure")).containsExactly("motor", "failure");
    }

    @Test
    void normalize_deduplicates() {
        String[] tokens = normalizer.normalize("motor motor motor failure motor");
        assertThat(tokens).containsExactly("motor", "failure");
    }

    @Test
    void normalize_lowercases() {
        String[] tokens = normalizer.normalize("HVAC FAILURE OVERHEATING");
        assertThat(tokens).contains("hvac", "failure", "overheating");
    }

    @Test
    void overlap_count_common_tokens() {
        String[] a = {"water", "pump", "leaking"};
        String[] b = {"pump", "leaking", "inlet"};
        assertThat(FaultSignatureNormalizer.overlapCount(a, b)).isEqualTo(2);
    }

    @Test
    void overlap_count_no_common() {
        String[] a = {"motor", "failure"};
        String[] b = {"water", "pump"};
        assertThat(FaultSignatureNormalizer.overlapCount(a, b)).isZero();
    }

    @Test
    void overlap_count_empty_array_returns_zero() {
        assertThat(FaultSignatureNormalizer.overlapCount(new String[0], new String[]{"motor"})).isZero();
        assertThat(FaultSignatureNormalizer.overlapCount(new String[]{"motor"}, new String[0])).isZero();
    }

    @Test
    void overlap_count_null_returns_zero() {
        assertThat(FaultSignatureNormalizer.overlapCount(null, new String[]{"motor"})).isZero();
    }

    // ── Window boundary fixtures (pinned for determinism) ───────────────────

    @Test
    void normalize_within_window_boundary_exactly() {
        // Three-char token exactly at MIN_TOKEN_LENGTH boundary
        String[] tokens = normalizer.normalize("fan not working");
        assertThat(tokens).contains("fan", "working");
        assertThat(tokens).doesNotContain("not");
    }
}
