package com.fieldservice.platform.masking;

/**
 * Port through which the masking layer queries the classification tier for an entity field.
 *
 * <p>Defined in the platform module to avoid a circular Maven dependency
 * (privacy → platform; platform must not → privacy). The privacy module provides an
 * adapter implementation that delegates to {@code ClassificationRegistry}.
 *
 * <p>When no implementation is registered, {@link PiiMasker} defaults to
 * {@link MaskingTier#RESTRICTED} for every unclassified field — fail-secure.
 */
public interface ClassificationPort {

    /**
     * Returns the masking tier for the named field on the named entity.
     *
     * <p>Implementations should check the field-level row first, then fall back to
     * the entity-level row. If neither exists, return {@link MaskingTier#RESTRICTED}
     * (most restrictive — fail-secure default).
     *
     * @param entityName logical entity name (e.g. {@code "Customer"})
     * @param fieldName  field name (e.g. {@code "emailAddress"})
     * @return the resolved tier; never {@code null}
     */
    MaskingTier resolveFieldTier(String entityName, String fieldName);
}
