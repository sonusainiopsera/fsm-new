package com.fieldservice.platform.masking;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registry of per-data-type {@link MaskingStrategy} implementations.
 *
 * <p>Each strategy is a pure, idempotent, null-safe function:
 * <ul>
 *   <li>{@link #EMAIL} — keeps first character and domain suffix (e.g. {@code j***@example.com})</li>
 *   <li>{@link #PHONE} — keeps last two digits (e.g. {@code ***42})</li>
 *   <li>{@link #NAME} — keeps initials (e.g. {@code J.D.})</li>
 *   <li>{@link #ADDRESS} — redacted entirely (region resolution requires geocoding, out of scope)</li>
 *   <li>{@link #COORDINATE} — reduces lat/lon precision to 2 d.p. (city-level)</li>
 *   <li>{@link #TOKEN} — fully redacts</li>
 *   <li>{@link #HASH} — fully redacts</li>
 * </ul>
 *
 * <p>All strategies default to {@link PiiMasker#REDACTION_TOKEN} on malformed or empty input
 * rather than propagating exceptions, satisfying the fail-secure constraint.
 */
public final class MaskingStrategies {

    private static final String REDACTED = PiiMasker.REDACTION_TOKEN;

    // ── Per-type strategy constants ───────────────────────────────────────────

    /** j***@example.com — first char + *** + @domain */
    public static final MaskingStrategy EMAIL = value -> {
        if (value == null) return null;
        if (value.isBlank()) return REDACTED;
        // Already masked — idempotency
        if (value.contains("***@")) return value;
        int at = value.indexOf('@');
        if (at < 1) return REDACTED;
        String local = value.substring(0, at);
        String domain = value.substring(at); // includes @
        return local.charAt(0) + "***" + domain;
    };

    /** ***42 — last two digits only */
    public static final MaskingStrategy PHONE = value -> {
        if (value == null) return null;
        if (value.isBlank()) return REDACTED;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 2) return REDACTED;
        return "***" + digits.substring(digits.length() - 2);
    };

    /** J.D. — uppercase initials from whitespace-delimited tokens */
    public static final MaskingStrategy NAME = value -> {
        if (value == null) return null;
        if (value.isBlank()) return REDACTED;
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length == 0) return REDACTED;
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (!token.isEmpty()) {
                sb.append(Character.toUpperCase(token.charAt(0))).append('.');
            }
        }
        return sb.length() > 0 ? sb.toString() : REDACTED;
    };

    /**
     * Address — fully redacted (region-level component resolution requires geocoding;
     * deferred until the geocoding service SPI is defined).
     */
    public static final MaskingStrategy ADDRESS = value -> {
        if (value == null) return null;
        return REDACTED;
    };

    /**
     * Coordinate — reduces precision to 2 decimal places (city-level, ~1 km).
     * The original value never appears in the output.
     */
    public static final MaskingStrategy COORDINATE = value -> {
        if (value == null) return null;
        if (value.isBlank()) return REDACTED;
        // Already at reduced precision — idempotency check
        if (COORD_PATTERN.matcher(value).matches()) {
            String[] parts = value.split(",");
            if (parts.length == 2) {
                try {
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    return formatCoord(lat) + "," + formatCoord(lon);
                } catch (NumberFormatException ignored) {
                    // fall through to REDACTED
                }
            }
        }
        // Try to parse as "lat,lon"
        String trimmed = value.trim();
        int comma = trimmed.indexOf(',');
        if (comma > 0) {
            try {
                double lat = Double.parseDouble(trimmed.substring(0, comma).trim());
                double lon = Double.parseDouble(trimmed.substring(comma + 1).trim());
                // Validate range
                if (lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0) {
                    return formatCoord(lat) + "," + formatCoord(lon);
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return REDACTED;
    };

    /** TOKEN — fully redacted, no partial reveal */
    public static final MaskingStrategy TOKEN = value -> {
        if (value == null) return null;
        return REDACTED;
    };

    /** HASH — fully redacted (value IS the secret; do not emit even partial) */
    public static final MaskingStrategy HASH = value -> {
        if (value == null) return null;
        return REDACTED;
    };

    // ── Registry ──────────────────────────────────────────────────────────────

    /** Strategy looked up by canonical data-type key (upper-case). */
    private static final Map<String, MaskingStrategy> BY_TYPE = Map.of(
            "EMAIL",      EMAIL,
            "PHONE",      PHONE,
            "NAME",       NAME,
            "ADDRESS",    ADDRESS,
            "COORDINATE", COORDINATE,
            "TOKEN",      TOKEN,
            "HASH",       HASH
    );

    /**
     * Returns the strategy registered under the given type key, or
     * {@link #TOKEN} (full redaction) if the key is unrecognised.
     *
     * @param dataType canonical data type (case-insensitive)
     */
    public static MaskingStrategy forType(String dataType) {
        if (dataType == null) return TOKEN;
        return BY_TYPE.getOrDefault(dataType.toUpperCase(Locale.ROOT), TOKEN);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final Pattern COORD_PATTERN =
            Pattern.compile("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?");

    private static String formatCoord(double v) {
        // Truncate (not round) to avoid leaking precision through rounding artefacts
        double truncated = Math.floor(Math.abs(v) * 100.0) / 100.0;
        if (v < 0) truncated = -truncated;
        return String.format(Locale.ROOT, "%.2f", truncated);
    }

    private MaskingStrategies() {}
}
