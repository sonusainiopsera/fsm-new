package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view returned by {@code GET /api/v1/privacy/erasures/{id}}.
 * Contains no personal-data values — only opaque identifiers, timestamps and counts.
 *
 * @param id             tombstone UUID
 * @param subjectType    subject type discriminator (e.g. {@code "CUSTOMER"})
 * @param subjectId      subject UUID
 * @param erasedAt       when the key was destroyed
 * @param actor          principal name of the actor who triggered erasure
 * @param sections       list of erased sections with row counts
 * @param verifications  verification scope results
 * @param outcome        {@code COMPLETED}, {@code IDEMPOTENT_NOOP}, or {@code REFUSED}
 * @param refusalReason  populated only when outcome is {@code REFUSED}
 */
public record ErasureView(
        UUID id,
        String subjectType,
        UUID subjectId,
        Instant erasedAt,
        String actor,
        List<ErasedSection> sections,
        List<ScopeVerification> verifications,
        String outcome,
        String refusalReason
) {

    public record ErasedSection(String name, int rowCount) {}

    public record ScopeVerification(
            String scopeId,
            boolean plaintextFound,
            int recordsChecked,
            Instant checkedAt
    ) {}
}
