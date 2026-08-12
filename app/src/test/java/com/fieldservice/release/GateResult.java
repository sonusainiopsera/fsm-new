package com.fieldservice.release;

/**
 * Immutable result for one named gate.
 *
 * @param name         human-readable gate name (stable across runs)
 * @param status       PASS / FAIL / SKIP / ERROR
 * @param durationMs   wall-clock milliseconds for this gate
 * @param detail       human-readable detail (failure message or "ok")
 * @param failureClass coarse failure class; null when status is PASS or SKIP
 */
public record GateResult(
        String name,
        GateStatus status,
        long durationMs,
        String detail,
        FailureClass failureClass) {

    public static GateResult pass(String name, long durationMs) {
        return new GateResult(name, GateStatus.PASS, durationMs, "ok", null);
    }

    public static GateResult fail(String name, long durationMs, String detail, FailureClass cls) {
        return new GateResult(name, GateStatus.FAIL, durationMs, detail, cls);
    }

    public static GateResult skip(String name, String reason) {
        return new GateResult(name, GateStatus.SKIP, 0, reason, null);
    }

    public static GateResult error(String name, long durationMs, Throwable t) {
        String detail = t.getClass().getSimpleName() + ": " + t.getMessage();
        return new GateResult(name, GateStatus.ERROR, durationMs, detail, FailureClass.TRANSPORT);
    }

    public boolean passed()  { return status == GateStatus.PASS; }
    public boolean failed()  { return status == GateStatus.FAIL || status == GateStatus.ERROR; }
    public boolean skipped() { return status == GateStatus.SKIP; }
}
