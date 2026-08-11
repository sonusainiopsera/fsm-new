package com.fieldservice.identity.token;

import java.time.Duration;

/**
 * Denylist of revoked JWT identifiers (jti claims).
 *
 * <p>On every authenticated request the resource server performs one {@code EXISTS} lookup
 * against the denylist before accepting the token. A positive match (token is revoked) or
 * a store failure (Redis unavailable) both result in rejection — the denylist is fail-closed.
 */
public interface JtiDenylist {

    /**
     * Returns {@code true} if the given jti has been explicitly revoked.
     *
     * @throws DenylistUnavailableException if the backing store is unreachable
     */
    boolean isRevoked(String jti);

    /**
     * Adds the given jti to the denylist for the specified duration.
     * Callers set the TTL equal to the token's residual lifetime so keys
     * auto-expire without manual cleanup.
     *
     * @throws DenylistUnavailableException if the backing store is unreachable
     */
    void revoke(String jti, Duration ttl);

    /** Thrown when the denylist backing store (Redis) is unavailable. */
    class DenylistUnavailableException extends RuntimeException {
        public DenylistUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
