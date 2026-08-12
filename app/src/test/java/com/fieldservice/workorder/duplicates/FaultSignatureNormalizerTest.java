package com.fieldservice.workorder.duplicates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaultSignatureNormalizerTest {

    @Test
    void normalize_lowercasesAndRemovesStopWords() {
        String result = FaultSignatureNormalizer.normalize("The boiler is not working");
        assertThat(result).doesNotContain("the", "is", "not");
        assertThat(result).contains("boiler").contains("working");
    }

    @Test
    void normalize_removesShortTokens() {
        String result = FaultSignatureNormalizer.normalize("AC unit fault x");
        assertThat(result).doesNotContain(" x ");
        assertThat(result).contains("unit").contains("fault");
    }

    @Test
    void normalize_deduplicatesTokens() {
        String result = FaultSignatureNormalizer.normalize("boiler boiler fault fault");
        long count = java.util.Arrays.stream(result.split(" "))
                .filter("boiler"::equals).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void normalize_emptyOrNullReturnsEmpty() {
        assertThat(FaultSignatureNormalizer.normalize(null)).isEmpty();
        assertThat(FaultSignatureNormalizer.normalize("  ")).isEmpty();
        assertThat(FaultSignatureNormalizer.normalize("the a an")).isEmpty();
    }

    @Test
    void jaccard_identicalSignaturesReturnOne() {
        String sig = FaultSignatureNormalizer.normalize("boiler fault heating system");
        assertThat(FaultSignatureNormalizer.jaccard(sig, sig)).isEqualTo(1.0);
    }

    @Test
    void jaccard_disjointSignaturesReturnZero() {
        String a = FaultSignatureNormalizer.normalize("boiler heating fault");
        String b = FaultSignatureNormalizer.normalize("electrical wiring panel");
        assertThat(FaultSignatureNormalizer.jaccard(a, b)).isEqualTo(0.0);
    }

    @Test
    void jaccard_partialOverlap() {
        String a = FaultSignatureNormalizer.normalize("boiler fault heating");
        String b = FaultSignatureNormalizer.normalize("boiler fault plumbing");
        double score = FaultSignatureNormalizer.jaccard(a, b);
        // intersection={boiler,fault} union={boiler,fault,heating,plumbing} → 2/4=0.5
        assertThat(score).isEqualTo(0.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void jaccard_emptySignatureReturnsZero() {
        assertThat(FaultSignatureNormalizer.jaccard("", "boiler fault")).isEqualTo(0.0);
        assertThat(FaultSignatureNormalizer.jaccard("boiler fault", "")).isEqualTo(0.0);
    }

    @Test
    void windowBoundary_onlyStopWordsProducesNoOverlap() {
        String a = FaultSignatureNormalizer.normalize("the a an is");
        String b = FaultSignatureNormalizer.normalize("boiler fault");
        assertThat(FaultSignatureNormalizer.jaccard(a, b)).isEqualTo(0.0);
    }
}
