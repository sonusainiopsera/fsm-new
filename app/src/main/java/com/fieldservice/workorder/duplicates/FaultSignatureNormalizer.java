package com.fieldservice.workorder.duplicates;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalises a fault description into a deterministic, explainable token set.
 * No external NLP or ML service — rules are: lowercase, strip punctuation,
 * remove stop words, deduplicate, minimum token length 2, alphabetical order.
 */
public final class FaultSignatureNormalizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "in", "on", "at", "to",
            "for", "of", "and", "or", "not", "it", "its", "with", "from", "by",
            "this", "that", "has", "have", "had", "be", "been", "into", "no",
            "we", "i", "my", "our", "he", "she", "they", "their", "us", "as",
            "up", "do", "did", "so", "if", "but", "all", "also", "any"
    );

    private FaultSignatureNormalizer() {}

    /**
     * Returns a space-separated sorted token string derived from {@code description}.
     * Returns empty string when the description produces no meaningful tokens.
     */
    static String normalize(String description) {
        if (description == null || description.isBlank()) return "";
        String cleaned = description.toLowerCase().replaceAll("[^a-z0-9 ]", " ");
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(t -> t.length() >= 2)
                .filter(t -> !STOP_WORDS.contains(t))
                .distinct()
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /**
     * Computes the Jaccard coefficient between two normalised token strings.
     * Returns 0.0 when either signature is empty (no meaningful overlap possible).
     */
    static double jaccard(String sigA, String sigB) {
        if (sigA == null || sigA.isBlank() || sigB == null || sigB.isBlank()) return 0.0;
        Set<String> a = Set.of(sigA.split(" "));
        Set<String> b = Set.of(sigB.split(" "));
        long intersection = a.stream().filter(b::contains).count();
        long union = (long) a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * Returns the individual tokens from a normalised signature string.
     */
    static List<String> tokens(String signature) {
        if (signature == null || signature.isBlank()) return List.of();
        return List.of(signature.split(" "));
    }
}
