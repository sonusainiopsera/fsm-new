package com.fieldservice.privacy.internal;

import com.fieldservice.workorder.lifecycle.GuardResult;

/**
 * Extension point for pre-transition business rules on DSAR requests.
 *
 * <p>Guards are referenced by identifier in {@link DsarTransitionTable} and evaluated
 * in order before the state change is committed. Guards must be side-effect-free
 * and must never fail open.
 */
interface DsarTransitionGuard {

    /** Stable identifier matching the guard name declared in the transition table. */
    String guardId();

    /**
     * Evaluate whether the transition is permissible.
     *
     * @param request the aggregate being transitioned (read-only in guard context)
     * @param event   the event requesting the transition
     * @param context request-scoped context data
     * @return {@link GuardResult.Satisfied} to permit or {@link GuardResult.Refused} to block
     */
    GuardResult evaluate(DsarRequest request, DsarEvent event, DsarTransitionContext context);
}
