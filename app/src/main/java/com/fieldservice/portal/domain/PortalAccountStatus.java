package com.fieldservice.portal.domain;

/** Lifecycle status of a portal account linkage row. */
public enum PortalAccountStatus {
    /** Invitation issued but not yet activated. */
    PENDING,
    /** Activation completed; user can log in to the portal. */
    ACTIVE,
    /** Account access suspended by an administrator. */
    SUSPENDED
}
