package com.fieldservice.platform.crypto;

/**
 * Manages per-data-subject envelope encryption keys.
 *
 * <h2>Envelope pattern</h2>
 * A master key held in the managed key service wraps a per-subject AES-256 data key.
 * The wrapped key is stored in {@code subject_data_key}; the plaintext key is
 * returned only at generation and resolution time and is NEVER persisted.
 *
 * <h2>State machine</h2>
 * <pre>
 *   generate() → ACTIVE
 *   rotate()   → old: ROTATED, new: ACTIVE
 *   destroy()  → DESTROYED (terminal, idempotent)
 * </pre>
 *
 * <h2>Fail-closed contract</h2>
 * Implementations must throw {@link KeyManagementUnavailableException} when the underlying
 * key service cannot be reached.  Callers must never fall back to plaintext storage.
 */
public interface SubjectKeyManager {

    /**
     * Generates and persists a new ACTIVE key for the given subject.
     * If an ACTIVE key already exists, this is a no-op and the existing key is returned.
     *
     * @throws KeyManagementUnavailableException if the key service is unavailable
     */
    SubjectKeySpec generate(SubjectRef subject);

    /**
     * Resolves the current ACTIVE key for the given subject, generating one if none exists.
     *
     * @throws KeyManagementUnavailableException if the key service is unavailable
     */
    SubjectKeySpec resolveActive(SubjectRef subject);

    /**
     * Resolves a specific key version for the given subject (used during decryption of
     * ciphertext produced under a prior version).
     *
     * @throws KeyManagementUnavailableException if the key service is unavailable
     * @throws SubjectKeyDestroyedException      if the requested key version has been destroyed
     */
    SubjectKeySpec resolve(SubjectRef subject, int keyVersion);

    /**
     * Generates a new ACTIVE key version for the subject and marks the previous ACTIVE
     * version as ROTATED.  Existing ciphertext produced under the old version remains
     * readable; only new writes use the rotated-in key.
     *
     * @throws KeyManagementUnavailableException if the key service is unavailable
     */
    void rotate(SubjectRef subject);

    /**
     * Irrevocably destroys all key versions for the given subject.
     * This operation is terminal and idempotent; subsequent calls are silent no-ops.
     * After destroy, {@link #resolve} on any version returns
     * {@link SubjectKeyDestroyedException} instead of key material.
     *
     * @throws KeyManagementUnavailableException if the key service is unavailable
     */
    void destroy(SubjectRef subject);
}
