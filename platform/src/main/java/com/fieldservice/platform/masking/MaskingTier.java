package com.fieldservice.platform.masking;

/**
 * Data classification tier as understood by the masking layer.
 *
 * <p>Mirrors {@code ClassificationTier} from the privacy module. The platform module
 * cannot depend on the privacy module (it would create a circular dependency), so the
 * tier is re-expressed here. The {@code ClassificationPortAdapter} in the privacy module
 * maps between the two enums at the boundary.
 */
public enum MaskingTier {

    /** Publicly available reference data — pass through unchanged. */
    PUBLIC,

    /** Internal operational data — pass through unchanged. */
    INTERNAL,

    /** Personal or commercially sensitive data — partially masked per field type. */
    CONFIDENTIAL,

    /** Cryptographic material and credentials — fully redacted, never emitted. */
    RESTRICTED
}
