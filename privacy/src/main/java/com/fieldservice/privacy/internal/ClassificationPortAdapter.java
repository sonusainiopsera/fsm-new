package com.fieldservice.privacy.internal;

import com.fieldservice.platform.masking.ClassificationPort;
import com.fieldservice.platform.masking.MaskingTier;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.springframework.stereotype.Component;

/**
 * Adapts the privacy module's {@link ClassificationRegistry} to the platform's
 * {@link ClassificationPort} so the masking layer can resolve classification tiers
 * without a direct dependency from platform → privacy.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>Field-level row ({@code entityName + fieldName})</li>
 *   <li>Entity-level row ({@code entityName}, field {@code null})</li>
 *   <li>Default: {@link MaskingTier#RESTRICTED} (fail-secure)</li>
 * </ol>
 */
@Component
class ClassificationPortAdapter implements ClassificationPort {

    private final ClassificationRegistry registry;

    ClassificationPortAdapter(ClassificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MaskingTier resolveFieldTier(String entityName, String fieldName) {
        return registry.findByEntityAndField(entityName, fieldName)
                .map(ClassificationView::tier)
                .or(() -> registry.findByEntity(entityName).map(ClassificationView::tier))
                .map(this::toMaskingTier)
                .orElse(MaskingTier.RESTRICTED);
    }

    private MaskingTier toMaskingTier(ClassificationTier tier) {
        return switch (tier) {
            case PUBLIC       -> MaskingTier.PUBLIC;
            case INTERNAL     -> MaskingTier.INTERNAL;
            case CONFIDENTIAL -> MaskingTier.CONFIDENTIAL;
            case RESTRICTED   -> MaskingTier.RESTRICTED;
        };
    }
}
