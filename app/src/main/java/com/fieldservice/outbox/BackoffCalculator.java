package com.fieldservice.outbox;

import java.time.Instant;

/** Stateless utility for jittered exponential backoff calculations. */
public final class BackoffCalculator {

    private BackoffCalculator() {}

    /**
     * Returns the {@link Instant} at which the next attempt should be made.
     *
     * <p>Formula: {@code delay = min(cap, base * 2^attemptCount) * (1 + random * jitter)}.
     *
     * @param baseMs        base delay in milliseconds (must be > 0)
     * @param capMs         maximum delay in milliseconds
     * @param jitterFraction fraction of delay added as random jitter (0.0–1.0)
     * @param attemptCount  number of attempts already made (0-indexed)
     * @param now           reference instant for the calculation
     */
    public static Instant nextAttemptAt(long baseMs, long capMs, double jitterFraction,
                                        int attemptCount, Instant now) {
        int shift = Math.min(attemptCount, 30);
        long exponential = Math.min(capMs, baseMs * (1L << shift));
        long jitter = (long) (exponential * jitterFraction * Math.random());
        return now.plusMillis(exponential + jitter);
    }
}
