package com.fieldservice.platform.privacy;

/**
 * Classification tier for the platform masking layer.
 *
 * <p>Mirrors {@code ClassificationTier} in the privacy module without creating a
 * circular dependency. Values must remain in sync; the
 * {@code AnnotationFieldTierProvider} in the privacy module maps
 * {@code ClassificationTier} to this enum.
 */
public enum MaskingTier {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
