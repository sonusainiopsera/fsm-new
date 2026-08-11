package com.fieldservice.idempotency;

/**
 * Lifecycle state for an {@link IdempotencyKeyRecord}.
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS} — the request has been claimed; a concurrent retry receives
 *       a documented in-progress conflict response rather than a silent duplicate execution.</li>
 *   <li>{@link #COMPLETED} — the request finished with a 2xx status; the stored response
 *       is replayed on any subsequent identical request within the 24-hour retention window.</li>
 *   <li>{@link #NON_REPLAYABLE} — the response body exceeded the capture size limit;
 *       identical retries receive a documented non-replayable conflict rather than a truncated body.</li>
 * </ul>
 */
public enum IdempotencyState {
    IN_PROGRESS,
    COMPLETED,
    NON_REPLAYABLE
}
