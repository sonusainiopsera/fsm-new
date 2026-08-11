package com.fieldservice.workorder.application;

import java.util.UUID;

/**
 * Thrown when the supplied site does not belong to the supplied customer.
 * Maps to HTTP 422 via the global exception handler with code SITE_CUSTOMER_MISMATCH.
 */
public class SiteCustomerMismatchException extends RuntimeException {

    private final UUID siteId;
    private final UUID customerId;

    public SiteCustomerMismatchException(UUID siteId, UUID customerId) {
        super("Site " + siteId + " does not belong to customer " + customerId);
        this.siteId     = siteId;
        this.customerId = customerId;
    }

    public UUID getSiteId()     { return siteId; }
    public UUID getCustomerId() { return customerId; }
}
