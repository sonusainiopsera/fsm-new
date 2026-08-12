package com.fieldservice.privacy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable view of a subject erasure tombstone.
 *
 * <p>Contains no personal-data values — only subject type, subject id (for correlation
 * with the authorising DSAR), and structural metadata about the erased sections.
 */
public record SubjectErasureView(
        UUID   id,
        UUID   dsarRequestId,
        String subjectType,
        UUID   subjectId,
        String keyReference,
        Instant erasedAt,
        String actor,
        List<ErasedSection> erasedSections,
        List<VerificationResult> verificationResults,
        String outcome,
        String refusalReason) {

    /** Non-identifying record of one erased section. */
    public record ErasedSection(String name, int rowCount) {}
}
