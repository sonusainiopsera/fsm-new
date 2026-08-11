package com.fieldservice.platform.crypto;

import java.util.UUID;

/**
 * Abstraction over the key-management service for per-subject envelope encryption.
 *
 * <p>Each data subject (technician, customer account) has one or more data-encryption
 * keys (DEKs) wrapped by a managed master key. The plaintext DEK is returned to the
 * caller in memory only and must never be logged, persisted, or included in payloads.
 *
 * <p>Implementations must be thread-safe.
 *
 * <h3>Key lifecycle</h3>
 * <pre>
 *   (new subject)
 *       │
 *       ▼
 *   ACTIVE ──rotate()──► old version → ROTATED
 *       │                new version → ACTIVE
 *       │
 *       └──destroy()──► all versions → DESTROYED (terminal, irreversible)
 * </pre>
 */
public interface SubjectKeyManager {

    /**
     * Returns the current (ACTIVE) plaintext DEK for the subject, generating one if it
     * does not yet exist.
     *
     * @throws KeyManagementUnavailableException if the key-management service is unreachable
     */
    DataKey getOrCreateKey(String subjectType, UUID subjectId);

    /**
     * Resolves a specific key version by subject type and ID, for cases where both are known.
     *
     * <p>If the key is in state {@link SubjectKeyState#DESTROYED}, returns a DataKey with
     * that state so callers can return {@link EnvelopeEncryptedStringConverter#UNRECOVERABLE_MARKER}.
     *
     * @throws KeyManagementUnavailableException if the key-management service is unreachable
     * @throws IllegalArgumentException          if the tuple has never been registered
     */
    DataKey resolveKey(String subjectType, UUID subjectId, int keyVersion);

    /**
     * Resolves a key version using only the subject UUID and key version.
     *
     * <p>Used by the converter's decrypt path, which extracts the subject ID from the
     * ciphertext envelope but does not store the subject type there.
     * Implementations may rely on the uniqueness of UUIDs across subject types.
     *
     * @throws KeyManagementUnavailableException if the key-management service is unreachable
     * @throws IllegalArgumentException          if no key with this (subjectId, keyVersion) exists
     */
    DataKey resolveKeyById(UUID subjectId, int keyVersion);

    /**
     * Generates a new key version for the subject, re-wrapping without decrypting any
     * existing ciphertext. The previous version transitions to {@link SubjectKeyState#ROTATED}.
     *
     * @return the new key version number
     * @throws KeyManagementUnavailableException if the key-management service is unreachable
     */
    int rotate(String subjectType, UUID subjectId);

    /**
     * Permanently destroys all key versions for the subject. After this call every
     * {@link #resolveKey} and {@link #resolveKeyById} call for this subject returns a
     * DESTROYED DataKey.
     *
     * <p>This operation is terminal and idempotent — calling it multiple times is safe.
     *
     * @throws KeyManagementUnavailableException if the key-management service is unreachable
     */
    void destroy(String subjectType, UUID subjectId);

    /** Returns the current state of the most recent key version for the subject. */
    SubjectKeyState getState(String subjectType, UUID subjectId);

    /**
     * Returns the current (latest) key version number for the subject, or 0 if no key
     * has ever been generated.
     */
    int currentVersion(String subjectType, UUID subjectId);

    /**
     * Evicts any cached plaintext DEK for the subject so that the next operation fetches
     * a fresh copy from the key-management service.
     */
    void evictCache(String subjectType, UUID subjectId);
}
