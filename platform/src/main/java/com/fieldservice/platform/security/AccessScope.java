package com.fieldservice.platform.security;

/**
 * Defines the row-level access scope for a caller.
 * Every collection query MUST apply a mandatory server-side AccessScope predicate.
 * There is no code path that returns domain records without evaluating the caller's
 * role and row scope — violation is an automated test failure.
 */
public record AccessScope(
        String principalId,
        Role role,
        String tenantId
) {

    /**
     * Platform-level roles that influence query predicates.
     */
    public enum Role {
        DISPATCHER,
        FIELD_TECHNICIAN,
        OPERATIONS_MANAGER,
        CUSTOMER,
        SYSTEM
    }

    public boolean isTenantScoped() {
        return tenantId != null && !tenantId.isBlank();
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    public boolean isSystemRole() {
        return role == Role.SYSTEM;
    }
}
