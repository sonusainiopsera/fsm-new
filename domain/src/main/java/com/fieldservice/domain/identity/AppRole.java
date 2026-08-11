package com.fieldservice.domain.identity;

/**
 * Ratified role vocabulary for the field-service platform.
 * Mirrored as a CHECK constraint on the role_assignment table so an
 * out-of-vocabulary value is rejected even by migration scripts.
 */
public enum AppRole {
    ADMIN,
    DISPATCHER,
    TECHNICIAN,
    MANAGER,
    CUSTOMER
}
