package com.fieldservice.privacy.internal;

import com.fieldservice.workorder.lifecycle.GuardResult;
import org.springframework.stereotype.Component;

/**
 * Guard that requires {@code verificationMethod} to be provided when recording
 * identity verification (the {@code VERIFY} event).
 *
 * <p>The guard is referenced in {@link DsarTransitionTable} by
 * {@link DsarTransitionTable#GUARD_VERIFICATION_METHOD_REQUIRED}.
 */
@Component
class VerificationMethodRequiredGuard implements DsarTransitionGuard {

    @Override
    public String guardId() {
        return DsarTransitionTable.GUARD_VERIFICATION_METHOD_REQUIRED;
    }

    @Override
    public GuardResult evaluate(DsarRequest request, DsarEvent event, DsarTransitionContext context) {
        if (context.verificationMethod() == null || context.verificationMethod().isBlank()) {
            return new GuardResult.Refused(
                    "VERIFICATION_METHOD_REQUIRED",
                    "A verificationMethod must be provided when recording identity verification. "
                    + "Allowed values: GOVERNMENT_ID, EMAIL_OTP, ACCOUNT_CONFIRM, EXTERNAL_MANUAL");
        }
        return new GuardResult.Satisfied();
    }
}
