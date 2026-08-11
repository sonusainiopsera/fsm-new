package com.fieldservice.workforce.api;

import java.util.UUID;

/**
 * Read-only view of a technician for dispatch consumption.
 * Mobile phone and position are excluded — callers use AvailabilityPort for availability.
 */
public record TechnicianSummary(
        UUID   id,
        UUID   userId,
        String employeeCode,
        String displayName,
        String timezone,
        UUID   homeBaseSiteId,
        boolean active
) {}
