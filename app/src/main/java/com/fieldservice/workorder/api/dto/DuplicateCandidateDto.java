package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.duplicates.DuplicateCandidate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API projection of a duplicate candidate, as returned in creation responses and
 * GET /api/v1/work-orders/{id}/duplicate-candidates.
 */
public record DuplicateCandidateDto(
        UUID workOrderId,
        String reference,
        WorkOrderState state,
        Instant createdAt,
        double ageHours,
        List<String> basis,
        int signatureOverlapScore
) {
    public static DuplicateCandidateDto from(DuplicateCandidate c) {
        return new DuplicateCandidateDto(
                c.workOrderId(),
                c.reference(),
                c.state(),
                c.createdAt(),
                c.ageHours(),
                c.basis().stream().map(DuplicateCandidate.BasisTag::name).toList(),
                c.signatureOverlapCount());
    }
}
