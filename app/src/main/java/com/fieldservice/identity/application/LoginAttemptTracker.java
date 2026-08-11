package com.fieldservice.identity.application;

/**
 * Tracks failed login attempts per account to enforce the per-account lockout policy.
 *
 * <p>The key used internally is a SHA-256 hex hash of the canonicalized email address
 * (never the plaintext). The 900-second TTL starts on the first failure and is not
 * reset by subsequent failures so that an attacker cannot extend the observation window
 * indefinitely.
 *
 * <p>Implementations must be fail-closed: if the backing store is unavailable,
 * {@link #isLocked(String)} must throw rather than returning {@code false}, so the
 * application can return 503 rather than permitting unlimited guessing.
 */
public interface LoginAttemptTracker {

    /**
     * Records a failed login attempt for the given email hash.
     *
     * @param emailHash SHA-256 hex of the canonicalized email address
     * @return the updated failure count after recording this attempt
     * @throws RuntimeException if the backing store is unavailable
     */
    int recordFailure(String emailHash);

    /**
     * Clears the failure counter for the given email hash after a successful login.
     *
     * @param emailHash SHA-256 hex of the canonicalized email address
     */
    void recordSuccess(String emailHash);

    /**
     * Returns {@code true} if the account is currently locked (failure count ≥ threshold).
     *
     * @param emailHash SHA-256 hex of the canonicalized email address
     * @throws RuntimeException if the backing store is unavailable (fail-closed behaviour)
     */
    boolean isLocked(String emailHash);
}
