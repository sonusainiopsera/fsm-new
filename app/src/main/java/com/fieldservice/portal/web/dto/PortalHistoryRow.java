package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Redacted summary row for the customer service-history collection endpoint (WO-172).
 *
 * <h3>Approved fields (customer-permitted)</h3>
 * <ul>
 *   <li>{@code workOrderId} — stable identifier for drill-down links</li>
 *   <li>{@code reference} — human-readable reference code</li>
 *   <li>{@code statusLabel} — plain-language state phrase from {@code CustomerStateLabels}</li>
 *   <li>{@code siteName} — display name of the site from the {@code site} table</li>
 *   <li>{@code assetLabel} — asset name when linked; {@code null} when no asset is linked</li>
 *   <li>{@code openedAt} — creation timestamp; reliable for date-range filtering</li>
 *   <li>{@code closedAt} — last-state-change timestamp for terminal states (COMPLETED/CLOSED/CANCELLED);
 *       {@code null} for active work orders</li>
 *   <li>{@code outcomeSummary} — short plain-language outcome phrase for terminal states</li>
 * </ul>
 *
 * <h3>Forbidden fields (never exposed)</h3>
 * Technician GPS position, full technician identity (only first name + role via status endpoint),
 * raw internal state enum values, dispatch scores, override reasons, cost data, fault description.
 *
 * @param workOrderId    stable identifier
 * @param reference      human-readable WO reference code (nullable when not yet assigned)
 * @param statusLabel    approved customer-facing state label
 * @param siteName       display name of the site where work was performed
 * @param assetLabel     asset name (nullable — absent when no asset is linked)
 * @param openedAt       timestamp when the service request was created
 * @param closedAt       timestamp of terminal state transition; null for active WOs
 * @param outcomeSummary short outcome phrase for closed/completed/cancelled WOs; null for active
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortalHistoryRow(
        UUID workOrderId,
        String reference,
        String statusLabel,
        String siteName,
        String assetLabel,
        Instant openedAt,
        Instant closedAt,
        String outcomeSummary
) {
    /** Terminal states that have a closedAt timestamp and outcomeSummary. */
    public static boolean isTerminal(com.fieldservice.domain.workorder.WorkOrderState state) {
        return state == com.fieldservice.domain.workorder.WorkOrderState.COMPLETED
                || state == com.fieldservice.domain.workorder.WorkOrderState.CLOSED
                || state == com.fieldservice.domain.workorder.WorkOrderState.CANCELLED;
    }

    /** Plain-language outcome summary for terminal states; null for active states. */
    public static String outcomeSummary(com.fieldservice.domain.workorder.WorkOrderState state) {
        if (state == null) return null;
        return switch (state) {
            case COMPLETED -> "Work completed successfully";
            case CLOSED    -> "Service request closed";
            case CANCELLED -> "Work order cancelled";
            default        -> null;
        };
    }
}
