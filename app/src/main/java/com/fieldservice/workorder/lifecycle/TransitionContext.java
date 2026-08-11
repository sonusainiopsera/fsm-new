package com.fieldservice.workorder.lifecycle;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Immutable context passed to {@link TransitionGuard#evaluate} for guards that need
 * request-scoped data that is not on the {@link com.fieldservice.domain.workorder.WorkOrder} entity.
 *
 * @param holdReasonCode  the controlled hold reason code from the transition request; null when
 *                        the event is not HOLD or the caller omitted the field
 * @param transitionInstant the instant at which the transition is evaluated; used by
 *                          time-sensitive guards such as CertificationCurrencyGuard so tests
 *                          can pin a fixed clock
 */
public record TransitionContext(
        @Nullable String holdReasonCode,
        Instant transitionInstant
) {
    public static TransitionContext of(@Nullable String holdReasonCode) {
        return new TransitionContext(holdReasonCode, Instant.now());
    }
}
