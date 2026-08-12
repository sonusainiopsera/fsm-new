package com.fieldservice.workorder.duplicates;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Produces a deterministic, explainable token set from a free-text fault description.
 *
 * <p>Normalisation steps (in order):
 * <ol>
 *   <li>Lowercase (using ROOT locale for reproducibility across JVM locales).</li>
 *   <li>Replace non-alphanumeric characters with spaces.</li>
 *   <li>Split on whitespace into tokens.</li>
 *   <li>Drop tokens shorter than {@value #MIN_TOKEN_LENGTH} characters.</li>
 *   <li>Drop tokens that are English stop words.</li>
 *   <li>Deduplicate (preserve first occurrence order for determinism).</li>
 * </ol>
 *
 * <p>No stemming library is used — the intent is explainability over recall.
 * A fault description containing only stop words or very short tokens produces
 * an empty signature; detection falls back to asset/site matching alone.
 */
@Component
public class FaultSignatureNormalizer {

    private static final int MIN_TOKEN_LENGTH = 3;

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "it", "in", "on", "at", "to", "for",
            "of", "and", "or", "but", "this", "that", "has", "was", "not",
            "are", "be", "by", "we", "he", "she", "my", "you", "your",
            "our", "its", "they", "do", "did", "does", "have", "had",
            "with", "from", "up", "out", "as", "so", "if", "no", "can",
            "into", "via", "also", "been", "will", "there", "just"
    );

    /**
     * Returns the normalised token array for {@code faultDescription}.
     *
     * @param faultDescription raw fault text; may be null or blank
     * @return non-null array, possibly empty if all tokens were filtered out
     */
    public String[] normalize(String faultDescription) {
        if (faultDescription == null || faultDescription.isBlank()) {
            return new String[0];
        }

        String lower = faultDescription.toLowerCase(Locale.ROOT);
        String clean = lower.replaceAll("[^a-z0-9 ]", " ");
        String[] parts = clean.trim().split("\\s+");

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= MIN_TOKEN_LENGTH && !STOP_WORDS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * Computes the Jaccard-like overlap count between two token arrays.
     *
     * @return number of tokens present in both arrays; 0 if either is empty
     */
    public static int overlapCount(String[] a, String[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        Set<String> setB = Set.of(b);
        return (int) Arrays.stream(a).filter(setB::contains).count();
    }
}
