package com.fieldservice.workorder.technician;

import com.fieldservice.workorder.holds.HoldReasonResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full job-detail projection returned to technician mobile clients.
 *
 * <p>Extends the compact day-list summary with site access notes, full contact details,
 * asset identity, the allowed-transition set computed from the server transition table,
 * and the controlled hold-reason vocabulary.
 */
public record TechnicianJobDetail(
        UUID   id,
        String reference,
        String priority,
        String state,
        Instant scheduledWindowStart,
        Instant scheduledWindowEnd,

        // Site
        String siteName,
        String siteAddress,
        String siteAccessNotes,

        // Contact
        String contactName,
        String contactPhoneMasked,

        // Asset
        UUID   assetId,
        String assetTag,
        String assetDescription,

        // Fault
        String faultSummary,
        String faultCode,
        String faultCategory,

        // Required context
        List<String> requiredCertifications,
        List<ExpectedPart> expectedParts,

        // SLA
        Instant responseDeadline,
        Instant resolutionDeadline,
        boolean slaAtRisk,

        // Version for optimistic concurrency
        Integer version,

        // Server-driven action bar
        List<String>              allowedTransitions,
        List<HoldReasonResponse>  holdReasons
) {

    /** Compact part line for the technician detail view. */
    public record ExpectedPart(String partNumber, String name, int quantityRequired) {}
}
