package com.fieldservice.photoanalysis.internal;

import org.junit.jupiter.api.Test;

import static com.fieldservice.photoanalysis.internal.DescriptionOverrideClassifier.Classification.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DescriptionOverrideClassifierTest {

    private final DescriptionOverrideClassifier classifier = new DescriptionOverrideClassifier();

    // ── DISCARDED ─────────────────────────────────────────────────────────────

    @Test
    void nullDescription_discarded() {
        var result = classifier.classify("some suggestion", null);
        assertThat(result.classification()).isEqualTo(DISCARDED);
        assertThat(result.similarityScore()).isEqualTo(0.0);
    }

    @Test
    void blankDescription_discarded() {
        var result = classifier.classify("some suggestion", "   ");
        assertThat(result.classification()).isEqualTo(DISCARDED);
    }

    @Test
    void emptyDescription_discarded() {
        var result = classifier.classify("some suggestion", "");
        assertThat(result.classification()).isEqualTo(DISCARDED);
    }

    // ── ACCEPTED_UNCHANGED — no suggestion ───────────────────────────────────

    @Test
    void nullSuggestion_acceptedUnchanged() {
        var result = classifier.classify(null, "technician wrote this");
        assertThat(result.classification()).isEqualTo(ACCEPTED_UNCHANGED);
        assertThat(result.similarityScore()).isEqualTo(1.0);
    }

    @Test
    void blankSuggestion_acceptedUnchanged() {
        var result = classifier.classify("  ", "technician wrote this");
        assertThat(result.classification()).isEqualTo(ACCEPTED_UNCHANGED);
    }

    // ── ACCEPTED_UNCHANGED — identical text ──────────────────────────────────

    @Test
    void identicalText_acceptedUnchanged() {
        String text = "Pump seal failed; replaced with OEM part.";
        var result = classifier.classify(text, text);
        assertThat(result.classification()).isEqualTo(ACCEPTED_UNCHANGED);
        assertThat(result.similarityScore()).isEqualTo(1.0);
    }

    @Test
    void caseAndPunctuationDifference_acceptedUnchanged() {
        // Jaccard on token sets — case and punctuation normalised away
        var result = classifier.classify("Pump seal failed", "pump seal failed.");
        assertThat(result.classification()).isEqualTo(ACCEPTED_UNCHANGED);
        assertThat(result.similarityScore()).isCloseTo(1.0, within(0.01));
    }

    // ── LIGHTLY_EDITED ────────────────────────────────────────────────────────

    @Test
    void halfWordOverlap_lightlyEdited() {
        // A = {pump, seal, failed, replaced, with, oem, part} = 7 tokens
        // B = {pump, seal, failed, installed, new, component} = 6 tokens
        // intersection = {pump, seal, failed} = 3  union = 10  Jaccard = 0.3 → SUBSTANTIALLY_REWRITTEN
        // Let's build a case that gives ~0.7 overlap
        String s = "boiler pressure relief valve stuck open technician";
        String d = "boiler pressure relief valve stuck closed engineer";
        // tokens s: {boiler,pressure,relief,valve,stuck,open,technician} = 7
        // tokens d: {boiler,pressure,relief,valve,stuck,closed,engineer}  = 7
        // intersection: {boiler,pressure,relief,valve,stuck} = 5  union = 9  J = 5/9 ≈ 0.556
        var result = classifier.classify(s, d);
        assertThat(result.classification()).isEqualTo(LIGHTLY_EDITED);
        assertThat(result.similarityScore()).isCloseTo(5.0 / 9, within(0.01));
    }

    // ── SUBSTANTIALLY_REWRITTEN ───────────────────────────────────────────────

    @Test
    void noOverlap_substantiallyRewritten() {
        var result = classifier.classify("boiler pressure leak", "electrical fault tripped breaker");
        assertThat(result.classification()).isEqualTo(SUBSTANTIALLY_REWRITTEN);
        assertThat(result.similarityScore()).isEqualTo(0.0);
    }

    @Test
    void lowOverlap_substantiallyRewritten() {
        // intersection = 1, union = 9 → J ≈ 0.11
        var result = classifier.classify("alpha beta gamma delta", "alpha epsilon zeta eta theta iota");
        assertThat(result.classification()).isEqualTo(SUBSTANTIALLY_REWRITTEN);
        assertThat(result.similarityScore()).isLessThan(
                DescriptionOverrideClassifier.EDITED_THRESHOLD);
    }

    // ── Jaccard similarity unit tests ─────────────────────────────────────────

    @Test
    void jaccard_identicalSets_returnsOne() {
        double score = DescriptionOverrideClassifier.jaccardSimilarity("foo bar", "foo bar");
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void jaccard_disjointSets_returnsZero() {
        double score = DescriptionOverrideClassifier.jaccardSimilarity("alpha beta", "gamma delta");
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void jaccard_bothEmpty_returnsOne() {
        double score = DescriptionOverrideClassifier.jaccardSimilarity("", "");
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void jaccard_oneEmpty_returnsZero() {
        double score = DescriptionOverrideClassifier.jaccardSimilarity("foo bar", "");
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void jaccard_partialOverlap_correctValue() {
        // A={a,b,c} B={b,c,d}  intersection={b,c}=2  union={a,b,c,d}=4  J=0.5
        double score = DescriptionOverrideClassifier.jaccardSimilarity("a b c", "b c d");
        assertThat(score).isCloseTo(0.5, within(0.001));
    }

    @Test
    void jaccard_duplicateTokens_countOnce() {
        // "foo foo foo" and "foo bar" → sets A={foo}, B={foo,bar} → J = 1/2 = 0.5
        double score = DescriptionOverrideClassifier.jaccardSimilarity("foo foo foo", "foo bar");
        assertThat(score).isCloseTo(0.5, within(0.001));
    }
}
