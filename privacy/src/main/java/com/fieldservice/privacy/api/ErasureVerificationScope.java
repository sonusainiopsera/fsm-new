package com.fieldservice.privacy.api;

/**
 * Pluggable verification scope that asserts no plaintext personal data remains
 * retrievable for an erased subject through a particular channel.
 *
 * <p>Implementations are discovered automatically; adding a new scope requires
 * only a new {@code @Component} bean — no change to the erasure job.
 *
 * <p>Scopes provided by the platform:
 * <ul>
 *   <li>live-tables — queries live entity rows via registered SubjectDataProviders</li>
 *   <li>export-artifacts — checks that prior DSAR export JSON is disposed</li>
 * </ul>
 *
 * <p>Scopes provided by the app module:
 * <ul>
 *   <li>envers-audit — samples Envers *_AUD rows and asserts all encrypted values
 *       return the UNRECOVERABLE_MARKER</li>
 * </ul>
 *
 * <p>Contract: must never throw — return a result with an explanatory message
 * if the scope itself encounters an error, so one failing scope does not prevent
 * the others from running.
 */
public interface ErasureVerificationScope {

    /** Stable scope identifier used in the verification_result JSON. */
    String scopeName();

    /**
     * Verifies that no plaintext personal data is retrievable for {@code ref}
     * through this scope.
     *
     * @param ref the erased subject to verify
     * @return the verification result; {@link VerificationResult#plaintextFound()} is
     *         {@code true} if any readable PII was found (erasure incomplete for this scope)
     */
    VerificationResult verify(SubjectRef ref);
}
