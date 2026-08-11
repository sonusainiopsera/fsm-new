package com.fieldservice.identity.application;

/**
 * Tracks failed login attempts per account to enforce per-account lockout.
 *
 * <p>Keys are derived from an email hash (never plaintext) to avoid persisting PII
 * in Redis key space. The implementation must be atomic — concurrent failures for
 * the same account must increment the counter without losing a failure.
 *
 * <p>Fail-closed contract: implementations must throw {@link LoginAttemptStoreException}
 * when the backing store is unavailable, rather than silently allowing unlimited attempts.
 */
public interface LoginAttemptTracker {

    /**
     * Returns the current failure count for the given email hash.
     *
     * @throws LoginAttemptStoreException if the backing store cannot be reached
     */
    int getCount(String emailHash);

    /**
     * Atomically increments the failure counter and returns the new count.
     * Sets a TTL on first failure (window start).
     *
     * @throws LoginAttemptStoreException if the backing store cannot be reached
     */
    int recordFailure(String emailHash);

    /**
     * Clears the failure counter after a successful login.
     * Implementations must swallow errors here — a successful login must not be blocked
     * by a counter-reset failure.
     */
    void resetCounter(String emailHash);

    /** Thrown when the backing store is unavailable; triggers a 503 fail-closed response. */
    class LoginAttemptStoreException extends RuntimeException {
        public LoginAttemptStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
