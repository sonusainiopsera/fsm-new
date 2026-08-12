package com.fieldservice.sla;

/**
 * Controlled vocabulary for SLA breach root-cause attribution.
 *
 * <p>Values mirror the {@code reason_code} CHECK constraint in the sla_breach table.
 * An out-of-vocabulary value cannot be persisted even through direct SQL.
 */
public enum SlaBreachReasonCode {

    /** Required parts were unavailable, blocking completion. */
    PARTS_UNAVAILABLE,

    /** The customer or site was inaccessible when attendance was attempted. */
    CUSTOMER_ACCESS_DENIED,

    /** Insufficient workforce capacity to meet the commitment window. */
    CAPACITY_SHORTFALL,

    /** Travel disruption (traffic, transport failure) prevented timely attendance. */
    TRAVEL_DISRUPTION,

    /** Work order was assigned the wrong priority at intake, delaying dispatch. */
    MISPRIORITISED_AT_INTAKE,

    /** Any other cause; requires a free-text note. */
    OTHER
}
