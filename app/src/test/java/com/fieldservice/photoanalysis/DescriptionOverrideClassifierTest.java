package com.fieldservice.photoanalysis;

import com.fieldservice.photoanalysis.internal.DescriptionOverrideClassifier;
import com.fieldservice.photoanalysis.internal.DescriptionOverrideClassifier.ClassificationResult;
import com.fieldservice.photoanalysis.internal.DescriptionOverrideClassifier.OverrideClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DescriptionOverrideClassifier} — threshold boundary coverage.
 *
 * <p>Documents the Jaccard similarity thresholds:
 * <ul>
 *   <li>≥ 0.95 → ACCEPTED_UNCHANGED</li>
 *   <li>≥ 0.60 → LIGHTLY_EDITED</li>
 *   <li>≥ 0.15 → SUBSTANTIALLY_REWRITTEN</li>
 *   <li>&lt; 0.15 → DISCARDED</li>
 * </ul>
 */
class DescriptionOverrideClassifierTest {

    private final DescriptionOverrideClassifier classifier = new DescriptionOverrideClassifier();

    // ── Boundary: DISCARDED ────────────────────────────────────────────────────

    @Nested
    @DisplayName("DISCARDED classification")
    class Discarded {

        @Test
        @DisplayName("null final description with suggestion → DISCARDED, similarity 0.0")
        void nullFinalDiscarded() {
            ClassificationResult r = classifier.classify("Relay damaged on board", null);
            assertThat(r.classification()).isEqualTo(OverrideClassification.DISCARDED);
            assertThat(r.similarityScore()).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("blank final description with suggestion → DISCARDED, similarity 0.0")
        void blankFinalDiscarded() {
            ClassificationResult r = classifier.classify("Relay damaged on board", "   ");
            assertThat(r.classification()).isEqualTo(OverrideClassification.DISCARDED);
            assertThat(r.similarityScore()).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("completely different text produces DISCARDED (similarity < 0.15)")
        void completelyDifferentText() {
            String suggestion = "Relay damaged on main circuit board";
            String finalDesc  = "Coolant pipe leaking under pressure valve";
            ClassificationResult r = classifier.classify(suggestion, finalDesc);
            assertThat(r.classification()).isEqualTo(OverrideClassification.DISCARDED);
            assertThat(r.similarityScore()).isLessThan(BigDecimal.valueOf(0.15));
        }
    }

    // ── Boundary: SUBSTANTIALLY_REWRITTEN ────────────────────────────────────

    @Nested
    @DisplayName("SUBSTANTIALLY_REWRITTEN classification")
    class SubstantiallyRewritten {

        @Test
        @DisplayName("small overlap of tokens (Jaccard ≥ 0.15 but < 0.60) → SUBSTANTIALLY_REWRITTEN")
        void smallOverlapSubstantial() {
            // "board damaged relay" vs "relay faulty needs replacement immediately here"
            // tokens A: {board, damaged, relay}
            // tokens B: {relay, faulty, needs, replacement, immediately, here}
            // intersection: {relay}  = 1
            // union: {board, damaged, relay, faulty, needs, replacement, immediately, here} = 8
            // jaccard = 1/8 = 0.1250  → DISCARDED
            // Let's pick tokens with ~0.20 jaccard:
            // A: {relay, damaged, main, circuit, board, burn}  = 6 tokens
            // B: {relay, wiring, faulty, circuit, burnt}       = 5 tokens
            // intersection: {relay, circuit} = 2
            // union = 6+5-2 = 9
            // jaccard = 2/9 = 0.2222 → SUBSTANTIALLY_REWRITTEN
            String suggestion = "relay damaged main circuit board burn";
            String finalDesc  = "relay wiring faulty circuit burnt";
            ClassificationResult r = classifier.classify(suggestion, finalDesc);
            assertThat(r.classification()).isEqualTo(OverrideClassification.SUBSTANTIALLY_REWRITTEN);
            assertThat(r.similarityScore()).isBetween(
                    BigDecimal.valueOf(0.15), BigDecimal.valueOf(0.60));
        }
    }

    // ── Boundary: LIGHTLY_EDITED ──────────────────────────────────────────────

    @Nested
    @DisplayName("LIGHTLY_EDITED classification")
    class LightlyEdited {

        @Test
        @DisplayName("moderate overlap (Jaccard ≥ 0.60 but < 0.95) → LIGHTLY_EDITED")
        void moderateOverlapLightEdit() {
            // A: {relay, damaged, main, circuit, board} = 5
            // B: {relay, damaged, main, circuit, board, burnt} = 6
            // intersection = 5, union = 6
            // jaccard = 5/6 = 0.8333 → LIGHTLY_EDITED
            String suggestion = "relay damaged main circuit board";
            String finalDesc  = "relay damaged main circuit board burnt";
            ClassificationResult r = classifier.classify(suggestion, finalDesc);
            assertThat(r.classification()).isEqualTo(OverrideClassification.LIGHTLY_EDITED);
            assertThat(r.similarityScore()).isBetween(
                    BigDecimal.valueOf(0.60), BigDecimal.valueOf(0.95));
        }
    }

    // ── Boundary: ACCEPTED_UNCHANGED ─────────────────────────────────────────

    @Nested
    @DisplayName("ACCEPTED_UNCHANGED classification")
    class AcceptedUnchanged {

        @Test
        @DisplayName("identical text → ACCEPTED_UNCHANGED, similarity 1.0")
        void identicalText() {
            String text = "Relay damaged on main circuit board";
            ClassificationResult r = classifier.classify(text, text);
            assertThat(r.classification()).isEqualTo(OverrideClassification.ACCEPTED_UNCHANGED);
            assertThat(r.similarityScore()).isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("whitespace-only change → ACCEPTED_UNCHANGED (tokens identical)")
        void whitespaceChange() {
            String suggestion = "Relay  damaged   on board";
            String finalDesc  = "Relay damaged on board";
            ClassificationResult r = classifier.classify(suggestion, finalDesc);
            assertThat(r.classification()).isEqualTo(OverrideClassification.ACCEPTED_UNCHANGED);
        }

        @Test
        @DisplayName("case-only change → ACCEPTED_UNCHANGED (normalised lowercase)")
        void caseChange() {
            String suggestion = "RELAY DAMAGED ON BOARD";
            String finalDesc  = "relay damaged on board";
            ClassificationResult r = classifier.classify(suggestion, finalDesc);
            assertThat(r.classification()).isEqualTo(OverrideClassification.ACCEPTED_UNCHANGED);
        }

        @Test
        @DisplayName("no suggestion produced → ACCEPTED_UNCHANGED (no AI override)")
        void noSuggestion() {
            ClassificationResult r = classifier.classify(null, "technician authored text");
            assertThat(r.classification()).isEqualTo(OverrideClassification.ACCEPTED_UNCHANGED);
            assertThat(r.similarityScore()).isEqualByComparingTo("1.0000");
        }
    }

    // ── Jaccard static helper ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Jaccard similarity utility")
    class JaccardHelper {

        @Test
        @DisplayName("empty intersection = 0.0")
        void emptyIntersection() {
            BigDecimal j = DescriptionOverrideClassifier.jaccard("foo bar", "baz qux");
            assertThat(j).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("equal token sets = 1.0")
        void equalSets() {
            BigDecimal j = DescriptionOverrideClassifier.jaccard("foo bar baz", "baz foo bar");
            assertThat(j).isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("half overlap = 0.3333")
        void halfOverlap() {
            // A: {a, b, c}  B: {b, c, d}
            // intersection: {b, c} = 2, union: {a,b,c,d} = 4  → 0.5000
            BigDecimal j = DescriptionOverrideClassifier.jaccard("a b c", "b c d");
            assertThat(j).isEqualByComparingTo("0.5000");
        }
    }
}
