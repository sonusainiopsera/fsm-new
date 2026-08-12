package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarState;
import org.springframework.stereotype.Component;

/**
 * Guard {@code "dsar.identity.verified"}: enforces that identity has been verified
 * before the export job may claim or fulfil a request.
 *
 * <p>This guard is the structural enforcement of the constraint: no personal data
 * may leave the platform without recorded identity verification. It never fails open.
 */
@Component
class IdentityVerifiedGuard implements DsarTransitionGuard {

    @Override
    public String guardId() {
        return "dsar.identity.verified";
    }

    @Override
    public DsarGuardResult evaluate(DsarState fromState, DsarEvent event, DsarRequestEntity request) {
        if (request.getIdentityVerifiedAt() == null) {
            return new DsarGuardResult.Refused(
                    "IDENTITY_NOT_VERIFIED",
                    "Identity verification must be recorded before an export can be generated. "
                    + "Use POST /transitions with event RECORD_VERIFICATION or VERIFY_DIRECT first.");
        }
        return new DsarGuardResult.Satisfied();
    }
}
