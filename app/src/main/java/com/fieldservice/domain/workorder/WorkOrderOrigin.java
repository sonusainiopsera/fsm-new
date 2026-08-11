package com.fieldservice.domain.workorder;

/**
 * Origin of a work order creation request.
 *
 * <p>Persisted as a VARCHAR(20) column with a CHECK constraint. Used for objective O4
 * portal adoption reporting: the proportion of work orders originating from the PORTAL
 * channel is the primary indicator that customers are using self-service submission.
 */
public enum WorkOrderOrigin {

    /** Created by a customer via the self-service portal. */
    PORTAL,

    /** Created by a dispatcher or internal role through the back-office API. */
    DISPATCHER,

    /** Created via phone or walk-in intake and manually entered by front-office staff. */
    FRONT_OFFICE
}
