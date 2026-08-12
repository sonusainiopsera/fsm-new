package com.fieldservice.release;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable result record for a single named gate.
 *
 * @param name          Gate identifier, used as the artifact key.
 * @param status        Outcome of the evaluation.
 * @param durationMs    Wall-clock time of the gate run in milliseconds.
 * @param failureDetail Human-readable reason; null on PASS or SKIP.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GateResult(
        String name,
        GateStatus status,
        long durationMs,
        String failureDetail
) {

    /** Construct a passing result. */
    public static GateResult pass(String name, long durationMs) {
        return new GateResult(name, GateStatus.PASS, durationMs, null);
    }

    /** Construct a failing result with a detail message. */
    public static GateResult fail(String name, long durationMs, String detail) {
        return new GateResult(name, GateStatus.FAIL, durationMs, detail);
    }

    /** Construct a skipped result (dry-run mode or missing prerequisite). */
    public static GateResult skip(String name, String reason) {
        return new GateResult(name, GateStatus.SKIP, 0L, reason);
    }

    /** Construct a setup-error result. */
    public static GateResult setupError(String name, long durationMs, String detail) {
        return new GateResult(name, GateStatus.SETUP_ERROR, durationMs, detail);
    }

    /** Construct a transport-error result. */
    public static GateResult transportError(String name, long durationMs, String detail) {
        return new GateResult(name, GateStatus.TRANSPORT_ERROR, durationMs, detail);
    }

    /** True only when the gate confirms a genuine invariant violation or smoke failure. */
    public boolean isGateFailure() {
        return status == GateStatus.FAIL;
    }

    /** True when the environment prevented evaluation (not a gate failure). */
    public boolean isSetupProblem() {
        return status == GateStatus.SETUP_ERROR || status == GateStatus.TRANSPORT_ERROR;
    }
}
