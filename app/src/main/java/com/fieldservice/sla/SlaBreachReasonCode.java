package com.fieldservice.sla;

/**
 * Controlled vocabulary for SLA breach root-cause attribution.
 *
 * <p>Values mirror the {@code reason_code} CHECK constraint in the {@code sla_breach}
 * table so an out-of-vocabulary code cannot be persisted even through a raw SQL script.
 *
 * <p>Clients render exactly these options in the attribution UI so aggregated root-cause
 * reports group cleanly without free-text normalisation.
 */
public enum SlaBreachReasonCode {
    PARTS_UNAVAILABLE,
    CUSTOMER_ACCESS_DENIED,
    CAPACITY_SHORTFALL,
    TRAVEL_DISRUPTION,
    MISPRIORITISED_AT_INTAKE
}
