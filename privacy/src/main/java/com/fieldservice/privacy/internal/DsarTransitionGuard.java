package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarState;

/**
 * Strategy for a named DSAR lifecycle guard.
 * Guards never fail open: an unrecognised guard id must not permit the transition.
 */
interface DsarTransitionGuard {
    String guardId();
    DsarGuardResult evaluate(DsarState fromState, DsarEvent event, DsarRequestEntity request);
}
