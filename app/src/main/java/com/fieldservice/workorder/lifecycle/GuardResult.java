package com.fieldservice.workorder.lifecycle;

/**
 * Result returned by a {@link TransitionGuard} evaluation.
 *
 * <p>Pattern-match with Java 21 sealed types:
 * <pre>{@code
 * switch (result) {
 *     case GuardResult.Satisfied s  -> proceed();
 *     case GuardResult.Refused  r  -> throw new GuardRefusedException(r.code(), r.message());
 * }
 * }</pre>
 */
public sealed interface GuardResult {

    /** Guard evaluated successfully — the transition may proceed. */
    record Satisfied() implements GuardResult {}

    /** Guard refused the transition. */
    record Refused(String code, String message) implements GuardResult {}
}
