package com.fieldservice.privacy.api;

/**
 * Data classification tiers, ordered from least to most sensitive.
 *
 * <p>Tier definitions (from the architecture data-flow table):
 * <ul>
 *   <li>{@link #PUBLIC} — published externally; no handling restrictions</li>
 *   <li>{@link #INTERNAL} — internal operational data; not for external disclosure</li>
 *   <li>{@link #CONFIDENTIAL} — personal or commercially sensitive data; access-controlled</li>
 *   <li>{@link #RESTRICTED} — credentials and cryptographic material; never logged or transmitted</li>
 * </ul>
 */
public enum ClassificationTier {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
