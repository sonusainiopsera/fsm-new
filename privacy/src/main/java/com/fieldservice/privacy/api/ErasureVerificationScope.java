package com.fieldservice.privacy.api;

import java.time.Instant;

/**
 * Pluggable verification scope that asserts no retrievable plaintext personal data
 * remains after cryptographic erasure.
 *
 * <p>Each registered bean is invoked by the erasure job after key destruction.
 * A new scope can be added by implementing this interface and registering the bean
 * without changing the erasure job itself.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Never throw — return {@link VerificationResult#failed} instead.</li>
 *   <li>Query the primary (not replica) where possible to avoid false passes from lag.</li>
 *   <li>Never log plaintext personal-data values.</li>
 *   <li>Return a result within a reasonable timeout; the job will log a warning for slow scopes.</li>
 * </ul>
 */
public interface ErasureVerificationScope {

    /**
     * Returns a stable identifier for this scope, used in the persisted verification result.
     * Examples: {@code "live_tables"}, {@code "envers_audit"}, {@code "redis_cache"},
     * {@code "export_artifacts"}, {@code "outbox_events"}.
     */
    String scopeId();

    /**
     * Verifies that no retrievable plaintext personal data exists in this scope
     * for the given subject after key destruction.
     *
     * @param ref        the erased subject
     * @param checkedAt  timestamp to record in the result
     * @return the verification result for this scope
     */
    VerificationResult verify(SubjectRef ref, Instant checkedAt);

    /** Outcome of a single verification scope check. */
    record VerificationResult(
            String scopeId,
            boolean plaintextFound,
            int recordsChecked,
            String detail,
            Instant checkedAt
    ) {
        public static VerificationResult passed(String scopeId, int checked, Instant checkedAt) {
            return new VerificationResult(scopeId, false, checked, "No plaintext found", checkedAt);
        }

        public static VerificationResult failed(String scopeId, int found, String detail, Instant checkedAt) {
            return new VerificationResult(scopeId, true, found, detail, checkedAt);
        }
    }
}
