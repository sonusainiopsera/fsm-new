package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customer-facing redacted projection of a work order's current status.
 *
 * <p>Fields excluded by policy (absent from this type, never null-filled):
 * <ul>
 *   <li>Technician GPS position, phone, email, full name, employee identifier</li>
 *   <li>Raw internal state enum names or transition codes</li>
 *   <li>Dispatch scores, SLA policy identifiers, override reasons</li>
 *   <li>Cost, parts, or inventory data</li>
 *   <li>Data belonging to any other customer account</li>
 * </ul>
 *
 * <p>{@code appointmentWindow} and {@code technician} are optional (omitted when absent,
 * never null-filled) thanks to {@link JsonInclude#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortalStatusView(
        UUID             workOrderId,
        String           reference,
        String           statusLabel,
        String           statusDescription,
        Instant          respondByAt,
        Instant          resolveByAt,
        AppointmentWindow appointmentWindow,
        TechnicianSummary technician,
        List<PortalMilestone> milestones,
        Freshness        freshness
) {

    /**
     * Optional confirmed appointment window for the visit.
     * Present only when the dispatcher has committed a specific time slot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppointmentWindow(Instant fromAt, Instant toAt) {}

    /**
     * Policy-permitted subset of technician identity: first name and role label only.
     * Full name, phone, email and employee identifier are never included.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TechnicianSummary(String firstName, String roleLabel) {}

    /** A single customer-visible milestone event on the work order timeline. */
    public record PortalMilestone(Instant at, String label) {}

    /**
     * Self-declared freshness contract for the polling client.
     * {@code degraded} is true when any data source contributing to this response
     * was unavailable or older than the staleness budget (60 seconds).
     */
    public record Freshness(
            Instant observedAt,
            int     staleAfterSeconds,
            boolean degraded
    ) {}
}
