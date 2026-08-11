package com.fieldservice.domain.workorder.lifecycle;

/**
 * Result returned by a {@link TransitionGuard} evaluation.
 * Sealed so the compiler enforces exhaustive handling at call sites.
 */
public sealed interface GuardResult permits GuardResult.Satisfied, GuardResult.Refused {

    record Satisfied() implements GuardResult {}

    record Refused(String code, String message) implements GuardResult {}

    static GuardResult satisfied() {
        return new Satisfied();
    }

    static GuardResult refused(String code, String message) {
        return new Refused(code, message);
    }

    default boolean isSatisfied() {
        return this instanceof Satisfied;
    }
}
