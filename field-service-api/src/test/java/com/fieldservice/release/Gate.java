package com.fieldservice.release;

/**
 * A named, independently-reportable invariant gate.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Return {@link GateResult#skip} immediately when {@link RunConfig#dryRun()} is
 *       {@code true} and the gate performs writes.</li>
 *   <li>Clean up everything they create before returning.</li>
 *   <li>Never throw — catch all exceptions and convert to {@link GateResult#fail} or
 *       {@link GateResult#transportError}.</li>
 *   <li>Never include credentials, tokens, or PII in {@link GateResult#failureDetail()}.</li>
 * </ul>
 */
public interface Gate {

    /** Stable identifier used as the artifact key and in human-readable output. */
    String name();

    /**
     * Evaluate the gate.
     *
     * @param config  environment configuration for this run
     * @param client  pre-authenticated HTTP client (token refreshed automatically)
     * @return a non-null result record
     */
    GateResult run(RunConfig config, ApiClient client);
}
