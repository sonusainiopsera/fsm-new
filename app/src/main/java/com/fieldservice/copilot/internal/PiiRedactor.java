package com.fieldservice.copilot.internal;

import com.fieldservice.copilot.api.RedactionReport;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Replaces PII literals and patterns in free-text fields with stable opaque references.
 *
 * <p>Strategy (in order):
 * <ol>
 *   <li>Collect known PII literals from the loaded Customer and Site entities.</li>
 *   <li>Perform normalised, diacritic-insensitive, whole-token replacement of those literals.</li>
 *   <li>Apply pattern-based sweeps for email, phone and postcode shapes as defence in depth.</li>
 *   <li>Strip control characters and cap length per field.</li>
 * </ol>
 *
 * <p>Opaque references (CUSTOMER_1, SITE_1, CONTACT_1) are stable within a single request
 * but are NOT persisted — no re-identification map leaves the request scope.
 *
 * <p>Redaction is unconditional and cannot be disabled by configuration or request parameter.
 */
@Component
class PiiRedactor {

    // ── Pattern-based backstop sweeps ──────────────────────────────────────────

    private static final Pattern EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PHONE =
            Pattern.compile("(?<![\\d.])\\+?[0-9][\\s\\-.]?(?:[0-9][\\s\\-.]?){8,14}(?![\\d])");

    private static final Pattern POSTCODE =
            Pattern.compile("\\b[A-Z]{1,2}[0-9][0-9A-Z]?\\s*[0-9][A-Z]{2}\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    // ── Redaction state ─────────────────────────────────────────────────────────

    record RedactionResult(String text, int substitutionCount) {}

    /**
     * Mutable state for a single redaction pass. Holds the opaque reference counters
     * and accumulated report counts. Lives only for the duration of one
     * {@link #redact(GroundingContext, int)} call.
     */
    private static final class RedactionState {
        final Map<String, String> literalMap = new LinkedHashMap<>();
        int customerNameCount;
        int contactNameCount;
        int phoneCount;
        int emailCount;
        int addressCount;
        int postcodeCount;

        RedactionReport buildReport() {
            return new RedactionReport(
                    customerNameCount, contactNameCount, phoneCount,
                    emailCount, addressCount, postcodeCount);
        }
    }

    /**
     * Redacts all PII from the given context and returns a parallel structure with
     * safe opaque-reference text plus a {@link RedactionReport}.
     *
     * @param ctx            the raw grounding context
     * @param maxFreeTextLen maximum characters per free-text field; excess is truncated with a marker
     * @return a {@link RedactedGroundingContext} with all PII replaced
     */
    RedactedGroundingContext redact(GroundingContext ctx, int maxFreeTextLen) {
        var enrichment = ctx.enrichment();
        var state = new RedactionState();

        // Build known-literal map from domain entities
        buildLiteralMap(enrichment.customer(), enrichment.site(), state);

        // Redact main fault description
        String faultDesc = applyAll(enrichment.faultDescription(), state, maxFreeTextLen);

        // Redact prior work order free-text fields
        var redactedPrior = new ArrayList<RedactedPriorWo>();
        for (var prior : enrichment.priorWorkOrders()) {
            redactedPrior.add(new RedactedPriorWo(
                    prior.workOrderId(),
                    applyAll(prior.faultDescription(), state, maxFreeTextLen),
                    applyAll(prior.resolutionNotes(), state, maxFreeTextLen),
                    prior.partsConsumed()   // catalogue text — not PII
            ));
        }

        return new RedactedGroundingContext(faultDesc, enrichment.asset(), redactedPrior, state.buildReport());
    }

    // ── Known-literal map construction ─────────────────────────────────────────

    private void buildLiteralMap(Customer customer, Site site, RedactionState state) {
        if (customer != null) {
            putIfNotBlank(state.literalMap, customer.getName(),               "CUSTOMER_1");
            putIfNotBlank(state.literalMap, customer.getLegalName(),          "CUSTOMER_1");
            putIfNotBlank(state.literalMap, customer.getPrimaryContactName(), "CONTACT_1");
            putIfNotBlank(state.literalMap, customer.getPrimaryContactEmail(),"CONTACT_EMAIL_1");
            putIfNotBlank(state.literalMap, customer.getPrimaryContactPhone(),"CONTACT_PHONE_1");
            putIfNotBlank(state.literalMap, customer.getContactEmail(),       "CONTACT_EMAIL_1");
            putIfNotBlank(state.literalMap, customer.getContactPhone(),       "CONTACT_PHONE_1");
            putIfNotBlank(state.literalMap, customer.getBillingAddress(),     "ADDRESS_1");
        }
        if (site != null) {
            putIfNotBlank(state.literalMap, site.getName(),    "SITE_1");
            putIfNotBlank(state.literalMap, site.getAddress(), "SITE_ADDRESS_1");
            putIfNotBlank(state.literalMap, site.getPostcode(),"POSTCODE_1");
        }
    }

    private void putIfNotBlank(Map<String, String> map, String value, String replacement) {
        if (value != null && !value.isBlank()) {
            map.put(value, replacement);
        }
    }

    // ── Apply all redaction passes to a single string ──────────────────────────

    private String applyAll(String text, RedactionState state, int maxLen) {
        if (text == null) return null;
        if (text.isBlank()) return text;

        // 1. Strip control characters
        String working = CONTROL_CHARS.matcher(text).replaceAll("");

        // 2. Cap length
        if (working.length() > maxLen) {
            working = working.substring(0, maxLen) + " [TRUNCATED]";
        }

        // 3. Known-literal replacement (diacritic-insensitive, whole-word)
        working = applyLiteralReplacements(working, state);

        // 4. Pattern sweeps
        AtomicInteger phoneCount    = new AtomicInteger();
        AtomicInteger emailCount    = new AtomicInteger();
        AtomicInteger postcodeCount = new AtomicInteger();

        working = replaceWithCount(PHONE,    working, "[PHONE_REDACTED]",    phoneCount);
        working = replaceWithCount(EMAIL,    working, "[EMAIL_REDACTED]",    emailCount);
        working = replaceWithCount(POSTCODE, working, "[POSTCODE_REDACTED]", postcodeCount);

        state.phoneCount    += phoneCount.get();
        state.emailCount    += emailCount.get();
        state.postcodeCount += postcodeCount.get();

        return working;
    }

    private String applyLiteralReplacements(String text, RedactionState state) {
        // Sort by descending length so longer strings are replaced before shorter substrings
        List<Map.Entry<String, String>> sorted = new ArrayList<>(state.literalMap.entrySet());
        sorted.sort((a, b) -> b.getKey().length() - a.getKey().length());

        // Normalize working text once; all subsequent replacements operate on normalized text.
        // This handles diacritics: "Müller" → "muller" matches both forms.
        String working = normalize(text);

        for (var entry : sorted) {
            String literal     = entry.getKey();
            String replacement = entry.getValue();
            Pattern pattern    = buildLiteralPattern(normalize(literal));

            AtomicInteger count = new AtomicInteger();
            working = replaceWithCount(pattern, working, replacement, count);
            if (count.get() > 0) {
                accumulateCount(state, replacement, count.get());
            }
        }
        return working;
    }

    private void accumulateCount(RedactionState state, String replacement, int count) {
        if (replacement.startsWith("CUSTOMER")) state.customerNameCount += count;
        else if (replacement.startsWith("CONTACT")) state.contactNameCount += count;
        else if (replacement.startsWith("SITE_ADDRESS") || replacement.startsWith("ADDRESS")) state.addressCount += count;
        else if (replacement.startsWith("POSTCODE")) state.postcodeCount += count;
        else if (replacement.startsWith("SITE")) state.customerNameCount += count; // site name counted as customer label
    }

    private static String normalize(String s) {
        // NFD decomposition + lowercase for diacritic-insensitive matching
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase();
    }

    private static Pattern buildLiteralPattern(String normalizedLiteral) {
        // Whole-word boundary, case already lowered via normalise
        String escaped = Pattern.quote(normalizedLiteral);
        return Pattern.compile("(?i)(?<![\\p{L}\\p{Nd}])" + escaped + "(?![\\p{L}\\p{Nd}])");
    }

    private static String replaceWithCount(Pattern pattern, String text, String replacement, AtomicInteger counter) {
        return pattern.matcher(text).replaceAll(m -> {
            counter.incrementAndGet();
            return replacement;
        });
    }

    // ── Intermediate result type ────────────────────────────────────────────────

    record RedactedGroundingContext(
            String faultDescription,
            Asset asset,
            List<RedactedPriorWo> priorWorkOrders,
            RedactionReport report
    ) {}

    record RedactedPriorWo(
            java.util.UUID workOrderId,
            String faultDescription,
            String resolutionNotes,
            List<String> partsConsumed
    ) {}
}
