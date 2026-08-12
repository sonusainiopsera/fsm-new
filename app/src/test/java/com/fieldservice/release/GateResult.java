package com.fieldservice.release;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable result record returned by every invariant gate.
 *
 * <p>Three failure classes are explicitly distinguished:
 * <ul>
 *   <li>{@link Status#PASS} — invariant held.</li>
 *   <li>{@link Status#FAIL} — invariant was violated; triggers non-zero exit and rollback.</li>
 *   <li>{@link Status#SKIP} — gate was not executed (dry-run mode or write operations
 *       prohibited in this environment); must NOT be counted as a pass.</li>
 *   <li>{@link Status#SETUP_ERROR} — environment is misconfigured or a prerequisite is
 *       missing; the gate was not meaningful to run. Loud failure, not a gate pass.</li>
 * </ul>
 */
public record GateResult(
        String gateName,
        Status status,
        Duration duration,
        String detail
) {

    public enum Status { PASS, FAIL, SKIP, SETUP_ERROR }

    public static GateResult pass(String name, Duration duration, String detail) {
        return new GateResult(name, Status.PASS, duration, detail);
    }

    public static GateResult fail(String name, Duration duration, String detail) {
        return new GateResult(name, Status.FAIL, duration, detail);
    }

    public static GateResult skip(String name, String reason) {
        return new GateResult(name, Status.SKIP, Duration.ZERO, reason);
    }

    public static GateResult setupError(String name, String reason) {
        return new GateResult(name, Status.SETUP_ERROR, Duration.ZERO, reason);
    }

    public boolean passed()  { return status == Status.PASS; }
    public boolean failed()  { return status == Status.FAIL || status == Status.SETUP_ERROR; }
    public boolean skipped() { return status == Status.SKIP; }
}
