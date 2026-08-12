package com.fieldservice.privacy.api;

/**
 * How rows eligible for retention purge are disposed.
 *
 * <p>Soft-delete is explicitly forbidden as a disposal method; every purge path
 * must result in either physical deletion from the database or cryptographic erasure
 * of the associated subject key.
 */
public enum DisposalMethod {
    /** Physical deletion of the database row and any dependent child rows and object-storage objects. */
    PHYSICAL_DELETE,
    /** Invokes the WO-097 subject-key destruction path; the encrypted payload remains but is unreadable. */
    CRYPTO_ERASE
}
