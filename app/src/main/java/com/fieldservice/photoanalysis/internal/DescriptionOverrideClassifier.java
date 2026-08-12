package com.fieldservice.photoanalysis.internal;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies how a technician used an AI-suggested description.
 *
 * <h3>Algorithm</h3>
 * Normalised Jaccard similarity on whitespace-tokenised, lowercase tokens:
 * <pre>
 *   jaccard(A, B) = |tokens(A) ∩ tokens(B)| / |tokens(A) ∪ tokens(B)|
 * </pre>
 *
 * <h3>Classification thresholds (documented, deterministic)</h3>
 * <table border="1">
 *   <tr><th>Condition</th><th>Classification</th></tr>
 *   <tr><td>final text is null/blank (suggestion was present)</td><td>DISCARDED</td></tr>
 *   <tr><td>similarity ≥ 0.95</td><td>ACCEPTED_UNCHANGED</td></tr>
 *   <tr><td>similarity ≥ 0.60</td><td>LIGHTLY_EDITED</td></tr>
 *   <tr><td>similarity ≥ 0.15</td><td>SUBSTANTIALLY_REWRITTEN</td></tr>
 *   <tr><td>similarity &lt; 0.15</td><td>DISCARDED</td></tr>
 * </table>
 *
 * <p>When no suggestion was produced (null/blank suggestion) and the technician enters
 * text, the result is ACCEPTED_UNCHANGED with similarity 1.0 — the technician authored
 * the description without AI assistance.
 */
@Component
public class DescriptionOverrideClassifier {

    /** similarity ≥ this → ACCEPTED_UNCHANGED */
    public static final double THRESHOLD_UNCHANGED   = 0.95;
    /** similarity ≥ this → LIGHTLY_EDITED */
    public static final double THRESHOLD_LIGHT       = 0.60;
    /** similarity ≥ this → SUBSTANTIALLY_REWRITTEN */
    public static final double THRESHOLD_SUBSTANTIAL = 0.15;
    // below THRESHOLD_SUBSTANTIAL → DISCARDED

    public enum OverrideClassification {
        ACCEPTED_UNCHANGED,
        LIGHTLY_EDITED,
        SUBSTANTIALLY_REWRITTEN,
        DISCARDED
    }

    public record ClassificationResult(
            OverrideClassification classification,
            BigDecimal similarityScore
    ) {}

    /**
     * Classifies the technician's final description against the AI suggestion.
     *
     * @param suggestion        the AI-generated suggestion (may be null if analysis failed)
     * @param finalDescription  the description the technician will persist (may be null/blank)
     * @return classification result with similarity score in [0.0000, 1.0000]
     */
    public ClassificationResult classify(String suggestion, String finalDescription) {
        boolean suggestionBlank = suggestion == null || suggestion.isBlank();
        boolean finalBlank      = finalDescription == null || finalDescription.isBlank();

        // No suggestion was generated → no override to classify
        if (suggestionBlank) {
            return new ClassificationResult(
                    OverrideClassification.ACCEPTED_UNCHANGED,
                    BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
        }

        // Suggestion existed, technician cleared it
        if (finalBlank) {
            return new ClassificationResult(
                    OverrideClassification.DISCARDED,
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        }

        BigDecimal score = jaccard(suggestion, finalDescription);
        double d         = score.doubleValue();

        OverrideClassification cls;
        if (d >= THRESHOLD_UNCHANGED) {
            cls = OverrideClassification.ACCEPTED_UNCHANGED;
        } else if (d >= THRESHOLD_LIGHT) {
            cls = OverrideClassification.LIGHTLY_EDITED;
        } else if (d >= THRESHOLD_SUBSTANTIAL) {
            cls = OverrideClassification.SUBSTANTIALLY_REWRITTEN;
        } else {
            cls = OverrideClassification.DISCARDED;
        }

        return new ClassificationResult(cls, score);
    }

    /**
     * Computes Jaccard similarity between two strings.
     * Exposed as package-accessible for unit testing boundary values.
     */
    static BigDecimal jaccard(String a, String b) {
        Set<String> tokensA = tokenise(a);
        Set<String> tokensB = tokenise(b);

        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        }

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        if (union.isEmpty()) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        }

        return BigDecimal.valueOf(intersection.size())
                .divide(BigDecimal.valueOf(union.size()), 4, RoundingMode.HALF_UP);
    }

    private static Set<String> tokenise(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalised = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] parts = normalised.split("\\s+");
        return new HashSet<>(Arrays.asList(parts));
    }
}
