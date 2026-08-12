package com.fieldservice.platform.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Central masking service that resolves the applicable {@link MaskingStrategy}
 * for a class/field pair and applies it safely.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Consult {@link FieldTierProvider} for the field's tier.</li>
 *   <li>Apply the per-tier default strategy from {@link MaskingStrategies#forTier}.</li>
 *   <li>On any failure: fully redact and emit a masking-error log (never the raw value).</li>
 *   <li>Unclassified fields (provider returns empty) default to {@link MaskingTier#RESTRICTED}
 *       — the most restrictive treatment.</li>
 * </ol>
 */
@Component
public class PiiMasker {

    private static final Logger log = LoggerFactory.getLogger(PiiMasker.class);

    private final FieldTierProvider tierProvider;

    public PiiMasker(FieldTierProvider tierProvider) {
        this.tierProvider = tierProvider;
    }

    /**
     * Masks {@code value} according to the classification tier of
     * {@code fieldName} on {@code declaringClass}.
     *
     * <p>Never returns null; never throws; never leaks the original value on error.
     */
    public String mask(Class<?> declaringClass, String fieldName, String value) {
        try {
            Optional<MaskingTier> tier = tierProvider.getTier(declaringClass, fieldName);
            MaskingTier resolved = tier.orElse(MaskingTier.RESTRICTED);

            if (resolved == MaskingTier.PUBLIC || resolved == MaskingTier.INTERNAL) {
                return value == null ? "" : value;
            }
            return MaskingStrategies.forTier(resolved).mask(value);

        } catch (Exception e) {
            log.error("masking_error entity={} field={}", declaringClass.getSimpleName(), fieldName);
            return MaskingStrategy.REDACTED;
        }
    }

    /**
     * Applies {@code strategy} to {@code value}, failing closed to full redaction on error.
     */
    public String maskWithStrategy(MaskingStrategy strategy, String value) {
        try {
            return strategy.mask(value);
        } catch (Exception e) {
            log.error("masking_strategy_error value_type={}", value == null ? "null" : value.getClass().getSimpleName());
            return MaskingStrategy.REDACTED;
        }
    }

    /**
     * Returns true if the field is classified at RESTRICTED tier.
     */
    public boolean isRestricted(Class<?> declaringClass, String fieldName) {
        try {
            return tierProvider.getTier(declaringClass, fieldName)
                    .map(t -> t == MaskingTier.RESTRICTED)
                    .orElse(true); // unknown = treat as RESTRICTED
        } catch (Exception e) {
            return true;
        }
    }
}
