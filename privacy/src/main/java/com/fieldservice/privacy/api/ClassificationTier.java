package com.fieldservice.privacy.api;

/**
 * Data classification tier per the platform data-flow policy and GDPR/ISO 27001 register.
 *
 * <p>The four tiers are ordered from least to most sensitive. Every JPA entity and field
 * annotated with {@link DataClassification} must declare exactly one tier; the startup
 * consistency check enforces that a matching row exists in the {@code data_classification}
 * table.
 */
public enum ClassificationTier {

    /** Publicly available reference data — cacheable at edge, no access restriction. */
    PUBLIC,

    /** Internal operational data — role-restricted, retained per operations policy. */
    INTERNAL,

    /** Personal or commercially sensitive data — encrypted at rest, masked in logs. */
    CONFIDENTIAL,

    /** Cryptographic material and credentials — never logged, never exported, never in events. */
    RESTRICTED
}
