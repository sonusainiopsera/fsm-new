package com.fieldservice.portal.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redacted customer-facing status projection for a work order (WO-171).
 *
 * <h3>Forbidden fields (AC-2, AC-3)</h3>
 * The following are intentionally ABSENT from this record and must never be added:
 * <ul>
 *   <li>GPS position (latitude, longitude, lastKnownPosition)</li>
 *   <li>Full technician identity (fullName, phone, email, employeeNo)</li>
 *   <li>Internal state enum values (raw state codes, dispatch scores)</li>
 *   <li>Override reasons, cost data, or any other-account data</li>
 *   <li>Internal audit fields (revision numbers, actorUserId)</li>
 * </ul>
 *
 * <h3>Allowed technician identity</h3>
 * {@link TechnicianSummary} contains only {@code firstName} and {@code roleLabel} — the
 * minimum required to tell the customer who is coming, per the policy-permitted fields (AC-3).
 *
 * <h3>Freshness contract</h3>
 * {@link FreshnessInfo#degraded()} is {@code true} when a contributing sub-query failed
 * or was unavailable (e.g. hold-reason lookup, Envers history). The projection is still
 * returned with {@code 200} rather than {@code 500}; the client should surface a notice
 * that some information may be incomplete.
 */
public record PortalStatusView(

        UUID workOrderId,
        String reference,

        /** Plain-language status label (no internal enum names). */
        String statusLabel,

        /** Plain-language description appropriate for a non-expert reader (BR-33, BR-34). */
        String statusDescription,

        Instant respondByAt,
        Instant resolveByAt,

        /** Confirmed appointment window, or {@code null} if not yet scheduled. */
        AppointmentWindow appointmentWindow,

        /** Assigned technician's first name and role label only (no PII beyond what policy permits). */
        TechnicianSummary technician,

        /** Customer-relevant lifecycle milestones, newest last. */
        List<Milestone> milestones,

        FreshnessInfo freshness

) {

    /** Scheduled appointment window. {@code null} until the dispatcher confirms a window. */
    public record AppointmentWindow(Instant fromAt, Instant toAt) {}

    /** Policy-permitted technician identity — first name and role label only (AC-3). */
    public record TechnicianSummary(String firstName, String roleLabel) {}

    /** One customer-visible lifecycle event. */
    public record Milestone(Instant at, String label) {}

    /**
     * Freshness metadata required by the 60-second conditional-polling contract (AC-5).
     *
     * @param observedAt        timestamp at which the projection was assembled server-side
     * @param staleAfterSeconds client-side staleness budget (always 60 for the portal)
     * @param degraded          true when a sub-query failed; client should show a staleness notice
     */
    public record FreshnessInfo(Instant observedAt, int staleAfterSeconds, boolean degraded) {}
}
