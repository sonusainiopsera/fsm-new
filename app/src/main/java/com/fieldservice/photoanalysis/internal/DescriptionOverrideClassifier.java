package com.fieldservice.photoanalysis.internal;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies the technician's final description relative to the AI suggestion (WO-181 AC-6).
 *
 * <h3>Override classification (AC-6)</h3>
 * <pre>
 *   description is blank or null           → DISCARDED
 *   suggestion is blank/null               → ACCEPTED_UNCHANGED  (no suggestion to compare)
 *   Jaccard similarity ≥ ACCEPTED_THRESHOLD → ACCEPTED_UNCHANGED
 *   Jaccard similarity ≥ EDITED_THRESHOLD   → LIGHTLY_EDITED
 *   otherwise                              → SUBSTANTIALLY_REWRITTEN
 * </pre>
 *
 * <h3>Similarity rule (deterministic, documented)</h3>
 * Normalized Jaccard on token sets:
 * <pre>
 *   similarity = |A ∩ B| / |A ∪ B|
 * </pre>
 * where A and B are the sets of whitespace/punctuation-split, lowercased tokens from
 * the suggestion and final description respectively. Duplicate tokens count once.
 *
 * <h3>Thresholds</h3>
 * <ul>
 *   <li>{@link #ACCEPTED_THRESHOLD} = 0.95 — minor whitespace / capitalisation change</li>
 *   <li>{@link #EDITED_THRESHOLD} = 0.50 — roughly half the distinct words match</li>
 * </ul>
 *
 * Thresholds are documented here and in TESTING.md and are not configurable at runtime
 * so the reported 40-percent-substantial-rewrite metric is reproducible.
 */
@Component
class DescriptionOverrideClassifier {

    static final double ACCEPTED_THRESHOLD = 0.95;
    static final double EDITED_THRESHOLD   = 0.50;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s\\p{Punct}]+");

    /** Outcome vocabulary (AC-6). */
    enum Classification {
        ACCEPTED_UNCHANGED,
        LIGHTLY_EDITED,
        SUBSTANTIALLY_REWRITTEN,
        DISCARDED
    }

    record ClassificationResult(Classification classification, double similarityScore) {}

    /**
     * Classifies the final description relative to the AI suggestion.
     *
     * @param suggestion  the AI-suggested text (may be null or blank)
     * @param description the technician's final text (may be null or blank)
     * @return classification and Jaccard similarity score (0.0 when no comparison possible)
     */
    ClassificationResult classify(String suggestion, String description) {
        boolean descBlank = description == null || description.isBlank();
        if (descBlank) {
            return new ClassificationResult(Classification.DISCARDED, 0.0);
        }

        boolean suggBlank = suggestion == null || suggestion.isBlank();
        if (suggBlank) {
            return new ClassificationResult(Classification.ACCEPTED_UNCHANGED, 1.0);
        }

        double score = jaccardSimilarity(suggestion, description);

        Classification cls;
        if (score >= ACCEPTED_THRESHOLD) {
            cls = Classification.ACCEPTED_UNCHANGED;
        } else if (score >= EDITED_THRESHOLD) {
            cls = Classification.LIGHTLY_EDITED;
        } else {
            cls = Classification.SUBSTANTIALLY_REWRITTEN;
        }
        return new ClassificationResult(cls, score);
    }

    // ── Jaccard similarity ────────────────────────────────────────────────────

    static double jaccardSimilarity(String a, String b) {
        Set<String> setA = tokenize(a);
        Set<String> setB = tokenize(b);

        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(
                TOKEN_SPLIT.split(text.toLowerCase().strip())));
    }
}
