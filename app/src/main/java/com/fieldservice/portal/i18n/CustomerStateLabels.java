package com.fieldservice.portal.i18n;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.Map;

/**
 * Customer-facing plain-language labels for every work order lifecycle state (AC-7, BR-33, BR-34).
 *
 * <p>This is the single authoritative table shared by the API response and the web surface.
 * No internal state enum names, dispatch scores, or override reasons appear here.
 *
 * <p>The mapping is exhaustive: every value of {@link WorkOrderState} has an entry.
 * {@link #forState(WorkOrderState)} never returns {@code null}; unknown states fall through
 * to a safe default rather than throwing.
 */
public final class CustomerStateLabels {

    /** Approved customer-facing label and plain-language description for one lifecycle state. */
    public record StateLabel(String label, String description) {}

    private static final Map<WorkOrderState, StateLabel> LABELS = Map.of(
            WorkOrderState.NEW,
            new StateLabel(
                    "Request Received",
                    "Your service request has been logged and is awaiting assignment to a technician."),

            WorkOrderState.ASSIGNED,
            new StateLabel(
                    "Technician Assigned",
                    "A technician has been assigned and will be in contact with you shortly."),

            WorkOrderState.EN_ROUTE,
            new StateLabel(
                    "Technician En Route",
                    "Your technician is on their way to the site."),

            WorkOrderState.IN_PROGRESS,
            new StateLabel(
                    "Work in Progress",
                    "Your technician is on-site and working on the issue."),

            WorkOrderState.ON_HOLD,
            new StateLabel(
                    "Work Paused",
                    "Work has been temporarily paused. We will contact you with an update."),

            WorkOrderState.COMPLETED,
            new StateLabel(
                    "Work Complete",
                    "The work has been completed and is undergoing a final review before closure."),

            WorkOrderState.CLOSED,
            new StateLabel(
                    "Closed",
                    "Your service request has been successfully closed."),

            WorkOrderState.CANCELLED,
            new StateLabel(
                    "Cancelled",
                    "Your service request has been cancelled. Please contact us if you need further assistance.")
    );

    /** Customer-facing milestone label per internal event type. */
    public static final Map<String, String> MILESTONE_LABELS = Map.of(
            "CREATED",    "Request Received",
            "ASSIGNED",   "Technician Assigned",
            "DEPARTED",   "Technician En Route",
            "STARTED",    "Work Started",
            "RESUMED",    "Work Resumed",
            "HELD",       "Work Paused",
            "COMPLETED",  "Work Complete",
            "CLOSED",     "Closed",
            "CANCELLED",  "Cancelled",
            "REASSIGNED", "Technician Updated"
    );

    private CustomerStateLabels() {}

    /**
     * Returns the approved label and description for the given lifecycle state.
     * Never returns {@code null}; unknown states receive a safe "unknown" fallback.
     */
    public static StateLabel forState(WorkOrderState state) {
        if (state == null) {
            return new StateLabel("Unknown", "The current status is not available.");
        }
        return LABELS.getOrDefault(state,
                new StateLabel("Unknown", "The current status is not available."));
    }

    /**
     * Returns an ON_HOLD label enriched with the human-readable hold reason when available.
     *
     * @param holdReasonLabel the plain-language label from {@code hold_reason.label}, or {@code null}
     * @return the approved customer-facing label and enriched description
     */
    public static StateLabel forOnHoldWithReason(String holdReasonLabel) {
        String description = (holdReasonLabel != null && !holdReasonLabel.isBlank())
                ? "Work has been temporarily paused: " + holdReasonLabel
                  + ". We will be in touch as soon as work can resume."
                : "Work has been temporarily paused. We will contact you with an update.";
        return new StateLabel("Work Paused", description);
    }

    /**
     * Returns the approved customer-facing milestone label for an internal event type string.
     * Falls back to the raw event type if no mapping exists.
     */
    public static String milestoneLabel(String internalEventType) {
        return MILESTONE_LABELS.getOrDefault(internalEventType, internalEventType);
    }
}
