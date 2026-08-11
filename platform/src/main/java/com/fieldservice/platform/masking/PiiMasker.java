package com.fieldservice.platform.masking;

import com.fieldservice.platform.outbox.annotation.Confidential;
import com.fieldservice.platform.outbox.annotation.Restricted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central masking service for the platform.
 *
 * <p>Resolves the masking tier for a value via two sources (checked in order):
 * <ol>
 *   <li>A registered {@link ClassificationPort} bean (provided by the privacy module).</li>
 *   <li>The {@code @Confidential} / {@code @Restricted} annotations on the field.</li>
 * </ol>
 *
 * <p>If neither source provides classification, the field is treated as
 * {@link MaskingTier#RESTRICTED} — fail-secure.
 *
 * <p>Policy:
 * <ul>
 *   <li>{@code RESTRICTED} — replaced with {@link #REDACTION_TOKEN}</li>
 *   <li>{@code CONFIDENTIAL} — partially masked via the per-type {@link MaskingStrategy}</li>
 *   <li>{@code INTERNAL} / {@code PUBLIC} — passed through unchanged</li>
 * </ul>
 *
 * <p>Masking failures always result in full redaction — the original value never leaks.
 */
@Component
public class PiiMasker {

    /** Fixed sentinel emitted in place of RESTRICTED values. */
    public static final String REDACTION_TOKEN = "[REDACTED]";

    private static final Logger log = LoggerFactory.getLogger(PiiMasker.class);

    /** Cache of annotation-derived tiers to avoid repeated reflection. */
    private final Map<String, MaskingTier> annotationTierCache = new ConcurrentHashMap<>();

    @Nullable
    private final ClassificationPort classificationPort;

    public PiiMasker(@Nullable ClassificationPort classificationPort) {
        this.classificationPort = classificationPort;
    }

    /**
     * Masks a named field value using the resolved tier and per-type strategy.
     *
     * @param entityName JPA entity simple name (e.g. {@code "Customer"})
     * @param fieldName  field name (e.g. {@code "email"})
     * @param dataType   canonical data type for strategy selection (e.g. {@code "EMAIL"});
     *                   pass {@code null} to default to full redaction
     * @param value      the plaintext value; may be {@code null}
     * @return masked value; {@code null} if input was {@code null}
     */
    public String maskField(String entityName, String fieldName,
                            @Nullable String dataType, @Nullable String value) {
        if (value == null) return null;
        MaskingTier tier = resolveTier(entityName, fieldName);
        return applyTier(tier, dataType, value);
    }

    /**
     * Masks an annotated field value from a reflected {@link Field}.
     *
     * @param field     the field carrying {@code @Confidential} or {@code @Restricted}
     * @param dataType  canonical data type for strategy selection; pass {@code null} for full redact
     * @param value     the plaintext value
     * @return masked value; {@code null} if input was {@code null}
     */
    public String maskAnnotatedField(Field field, @Nullable String dataType,
                                     @Nullable String value) {
        if (value == null) return null;
        MaskingTier tier = tierFromAnnotation(field);
        return applyTier(tier, dataType, value);
    }

    /**
     * Returns the resolved {@link MaskingTier} for a named entity+field, checking
     * the {@link ClassificationPort} first, then defaulting to RESTRICTED.
     */
    public MaskingTier resolveTier(String entityName, String fieldName) {
        if (classificationPort != null) {
            try {
                return classificationPort.resolveFieldTier(entityName, fieldName);
            } catch (Exception e) {
                log.warn("masking_classification_lookup_failed entity={} field={} — defaulting to RESTRICTED",
                        entityName, fieldName);
            }
        }
        return MaskingTier.RESTRICTED;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String applyTier(MaskingTier tier, @Nullable String dataType, String value) {
        return switch (tier) {
            case RESTRICTED -> REDACTION_TOKEN;
            case CONFIDENTIAL -> {
                MaskingStrategy strategy = MaskingStrategies.forType(
                        dataType != null ? dataType : "TOKEN");
                try {
                    String result = strategy.mask(value);
                    yield result != null ? result : REDACTION_TOKEN;
                } catch (Exception e) {
                    log.warn("masking_strategy_failed dataType={} — fully redacting", dataType);
                    yield REDACTION_TOKEN;
                }
            }
            case INTERNAL, PUBLIC -> value;
        };
    }

    private MaskingTier tierFromAnnotation(Field field) {
        String key = field.getDeclaringClass().getName() + "#" + field.getName();
        return annotationTierCache.computeIfAbsent(key, k -> {
            if (field.isAnnotationPresent(Restricted.class)) return MaskingTier.RESTRICTED;
            if (field.isAnnotationPresent(Confidential.class)) return MaskingTier.CONFIDENTIAL;
            return MaskingTier.RESTRICTED; // fail-secure default
        });
    }
}
