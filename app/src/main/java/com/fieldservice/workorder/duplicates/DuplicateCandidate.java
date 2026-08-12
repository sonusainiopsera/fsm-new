package com.fieldservice.workorder.duplicates;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A candidate duplicate work order surfaced during detection, with an explainable similarity basis.
 *
 * @param workOrderId         UUID of the candidate
 * @param reference           human-readable reference (e.g. WO-00000042)
 * @param state               current lifecycle state
 * @param createdAt           when the candidate was created
 * @param ageHours            age of the candidate in hours at detection time
 * @param basis               which rules fired (ASSET_MATCH, SITE_MATCH, SIGNATURE_OVERLAP)
 * @param signatureOverlapCount  raw count of shared fault-signature tokens (0 if no text match)
 */
public record DuplicateCandidate(
        UUID workOrderId,
        String reference,
        WorkOrderState state,
        Instant createdAt,
        double ageHours,
        List<BasisTag> basis,
        int signatureOverlapCount
) {
    /** Explainable basis tag for a candidate. */
    public enum BasisTag {
        ASSET_MATCH,
        SITE_MATCH,
        SIGNATURE_OVERLAP
    }
}
