package com.fieldservice.dispatch.web.dto;

import com.fieldservice.inventory.api.PartsAvailabilityStatus;

import java.util.List;

/**
 * Per-candidate parts availability summary for the recommendations response (AC-4).
 *
 * @param status          availability verdict for this technician's van stock
 * @param satisfactionRatio fraction of required quantity satisfied from the van (0..1)
 * @param shortfalls      per-part shortfall detail; empty when status is FULLY_STOCKED
 */
public record PartsAvailabilitySummary(
        PartsAvailabilityStatus status,
        double satisfactionRatio,
        List<PartsShortfallEntry> shortfalls
) {
    public PartsAvailabilitySummary {
        shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
    }
}
