package com.fieldservice.privacy.api;

import java.time.Instant;

/**
 * Result of a single erasure verification scope run.
 *
 * <p>{@code plaintextFound} is true if any readable personal-data value was found for
 * the erased subject — i.e. the erasure was NOT complete for this scope.
 */
public record VerificationResult(
        String scope,
        boolean plaintextFound,
        int itemsChecked,
        Instant checkedAt) {

    public static VerificationResult clean(String scope, int itemsChecked, Instant checkedAt) {
        return new VerificationResult(scope, false, itemsChecked, checkedAt);
    }

    public static VerificationResult found(String scope, int itemsChecked, Instant checkedAt) {
        return new VerificationResult(scope, true, itemsChecked, checkedAt);
    }
}
