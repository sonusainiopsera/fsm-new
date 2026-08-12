package com.fieldservice.workorder.api.dto;

import com.fieldservice.workorder.holds.HoldReasonResponse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Full detail projection for a single work order in the technician mobile surface (WO-156 AC-1).
 *
 * <p>Contains everything the technician needs before and during the visit.
 * Contact phone is masked in this response. The {@code allowedTransitions} set is computed
 * server-side from the transition table and the caller's role — the client must never invent
 * a transition event.
 *
 * <p>{@code holdReasons} is embedded for convenience so the hold sheet does not need a
 * separate round-trip.
 */
public record TechnicianJobDetailResponse(

        UUID id,
        String reference,
        String state,
        String priority,
        String faultDescription,

        // ── Site ──────────────────────────────────────────────────────────────
        String siteName,
        String siteAddress,
        String sitePostcode,
        String siteAccessNotes,

        // ── Contact (masked) ─────────────────────────────────────────────────
        String contactName,
        String contactPhoneMasked,

        // ── Asset ─────────────────────────────────────────────────────────────
        UUID assetId,
        String assetTag,
        String assetDescription,
        String assetModel,
        String assetManufacturer,

        // ── Work requirements ─────────────────────────────────────────────────
        List<String> requiredCertifications,
        List<ExpectedPart> expectedParts,

        // ── Deadlines / SLA ───────────────────────────────────────────────────
        Instant responseDeadline,
        Instant resolutionDeadline,
        Instant atRiskAt,
        boolean slaAtRisk,

        // ── Server-driven action bar ──────────────────────────────────────────
        Set<String> allowedTransitions,
        List<HoldReasonResponse> holdReasons,

        Integer version

) {
    /**
     * Minimal part reference for display in the expected-parts list.
     */
    public record ExpectedPart(UUID partId, String partNumber, String partName, int requiredQuantity) {}
}
