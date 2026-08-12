package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer-facing redacted summary row for the portal service history collection.
 *
 * <p>Fields excluded by policy (absent from this type, never null-filled):
 * <ul>
 *   <li>Technician identity (name, phone, email, employee id)</li>
 *   <li>GPS position, dispatch scores, SLA policy identifiers</li>
 *   <li>Raw internal state enum names or transition codes</li>
 *   <li>Cost, parts, or inventory data</li>
 *   <li>Data belonging to any other customer account</li>
 * </ul>
 *
 * <p>Nullable optional fields ({@code assetLabel}, {@code closedAt},
 * {@code outcomeSummary}) are omitted from serialisation when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortalHistoryRow(
        UUID    workOrderId,
        String  reference,
        String  statusLabel,
        String  siteName,
        String  assetLabel,
        Instant openedAt,
        Instant closedAt,
        String  outcomeSummary
) {}
