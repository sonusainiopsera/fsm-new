package com.fieldservice.workorder.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compact projection DTO for the technician day-list API (WO-154).
 *
 * <p>Contains only the fields a technician needs before arriving on-site.
 * No entity graph is serialized — this record is assembled from WorkOrder + related entities
 * by {@link com.fieldservice.workorder.application.TechnicianDayQueryService}.
 *
 * <p>{@link #version} is excluded from the JSON response (used only for ETag computation).
 */
public record TechnicianJobSummary(
        UUID id,
        String reference,
        String priority,
        String state,
        Instant scheduledWindowStart,
        Instant scheduledWindowEnd,
        String siteName,
        String siteAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String assetTag,
        String assetDescription,
        String faultSummary,
        Instant resolutionDeadline,
        boolean slaAtRisk,
        String contactPhoneMasked,
        @JsonIgnore Integer version
) {}
