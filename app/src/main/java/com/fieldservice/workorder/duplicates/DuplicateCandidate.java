package com.fieldservice.workorder.duplicates;

import com.fieldservice.workorder.domain.WorkOrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Advisory duplicate candidate returned at creation time and on the on-demand endpoint.
 * Each candidate carries the explainable basis so dispatchers can verify the match.
 */
public record DuplicateCandidate(
        UUID workOrderId,
        String reference,
        WorkOrderStatus state,
        Instant createdAt,
        long ageHours,
        List<String> basis,
        double signatureOverlapScore) {

    /** Basis codes explaining why this work order was identified as a candidate. */
    public static final String ASSET_MATCH        = "ASSET_MATCH";
    public static final String SITE_MATCH         = "SITE_MATCH";
    public static final String SIGNATURE_OVERLAP  = "SIGNATURE_OVERLAP";
}
