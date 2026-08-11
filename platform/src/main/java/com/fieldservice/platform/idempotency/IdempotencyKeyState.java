package com.fieldservice.platform.idempotency;

/** Lifecycle state of a single idempotency-key claim. */
public enum IdempotencyKeyState {

    /** Request is currently being processed; lock is held. */
    IN_PROGRESS,

    /** Request completed with a 2xx response; stored for replay. */
    COMPLETED,

    /**
     * Request completed but the response body exceeded the size cap and was not stored.
     * A replay attempt with the same key will receive a 409 rather than a replayed body.
     */
    NON_REPLAYABLE
}
