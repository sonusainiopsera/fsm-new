package com.fieldservice.idempotency;

import java.util.UUID;

/**
 * Result of an idempotency-key claim attempt.
 */
public sealed interface ClaimResult
        permits ClaimResult.Claimed, ClaimResult.Replay, ClaimResult.Conflict,
                ClaimResult.InProgress, ClaimResult.NonReplayable {

    /** The key was successfully claimed; the request should proceed normally. */
    record Claimed(UUID recordId) implements ClaimResult {}

    /** An identical prior request completed; its response should be replayed. */
    record Replay(IdempotencyKeyRecord record) implements ClaimResult {}

    /**
     * The same key was reused with a different request payload; the operation
     * must not proceed and the caller should receive 409.
     */
    record Conflict() implements ClaimResult {}

    /**
     * A concurrent request with the same key is still in progress; the caller
     * should receive a documented 409 rather than a silent duplicate execution.
     */
    record InProgress() implements ClaimResult {}

    /**
     * The prior response body exceeded the capture size limit; the response
     * cannot be replayed.
     */
    record NonReplayable() implements ClaimResult {}

    // Factories

    static ClaimResult claimed(UUID recordId)               { return new Claimed(recordId); }
    static ClaimResult replay(IdempotencyKeyRecord record)  { return new Replay(record); }
    static ClaimResult conflict()                           { return new Conflict(); }
    static ClaimResult inProgress()                         { return new InProgress(); }
    static ClaimResult nonReplayable()                      { return new NonReplayable(); }
}
