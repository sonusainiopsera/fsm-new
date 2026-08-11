package com.fieldservice.identity.token;

import java.time.Duration;
import java.util.Optional;

/**
 * Persistence abstraction for single-use SSE stream tickets.
 *
 * <p>Tickets are stored as a hash of the opaque ticket value keyed by its SHA-256 digest,
 * with a 60-second TTL. Redemption is atomic: the payload is fetched and the key deleted in
 * a single operation so two concurrent redemptions yield exactly one accepted stream and one
 * rejection.
 *
 * <p>Implementations must fail closed: any store unavailability must throw
 * {@link StoreUnavailableException} so callers can return 503 rather than silently
 * issuing or redeeming a ticket.
 */
public interface StreamTicketStore {

    /**
     * Persists a ticket payload under the given key with the specified TTL.
     *
     * @param hashedKey the SHA-256 hex digest of the opaque ticket value
     * @param payload   payload to persist
     * @param ttl       time-to-live; must be positive
     * @throws StoreUnavailableException if the store cannot be reached
     */
    void store(String hashedKey, StreamTicketPayload payload, Duration ttl);

    /**
     * Atomically fetches and deletes the payload for the given key.
     *
     * <p>Returns {@link Optional#empty()} if the key does not exist (ticket expired or
     * already consumed). Returns the payload if the key existed, and guarantees the key is
     * deleted so no second caller can redeem the same ticket.
     *
     * @param hashedKey the SHA-256 hex digest of the opaque ticket value
     * @throws StoreUnavailableException if the store cannot be reached
     */
    Optional<StreamTicketPayload> consumeAtomically(String hashedKey);

    /** Thrown when the backing store is unreachable; callers must return 503. */
    class StoreUnavailableException extends RuntimeException {
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
