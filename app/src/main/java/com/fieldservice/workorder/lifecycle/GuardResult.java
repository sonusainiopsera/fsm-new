package com.fieldservice.workorder.lifecycle;

/** Result returned by a {@link TransitionGuard} evaluation. */
public sealed interface GuardResult {
    record Satisfied() implements GuardResult {}
    record Refused(String code, String message) implements GuardResult {}
}
