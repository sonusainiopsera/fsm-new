package com.fieldservice.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable snapshot of the acting principal's access scope, resolved once per request
 * from the authenticated JWT. Carries the minimal information the predicate factory
 * needs to construct row-level filter specifications.
 *
 * <p>Roles covered:
 * <ul>
 *   <li>ADMIN / DISPATCHER / MANAGER — tenant-wide (permit-all) access</li>
 *   <li>TECHNICIAN — only work orders where they are the current assignee</li>
 *   <li>CUSTOMER — only work orders for sites belonging to their linked accounts</li>
 * </ul>
 *
 * <p>A scope with an empty or unrecognised role set is treated as denied — callers
 * should not create such an instance; the resolver throws before returning it.
 */
public record AccessScope(
        String userId,
        Set<String> roles,
        String technicianId,
        Set<UUID> customerAccountIds
) {

    /** Compact constructor validates the invariant: roles must be non-null. */
    public AccessScope {
        if (roles == null) throw new IllegalArgumentException("roles must not be null");
        if (customerAccountIds == null) throw new IllegalArgumentException("customerAccountIds must not be null");
        roles = Set.copyOf(roles);
        customerAccountIds = Set.copyOf(customerAccountIds);
    }

    public boolean isAdmin()      { return roles.contains("ADMIN"); }
    public boolean isDispatcher() { return roles.contains("DISPATCHER"); }
    public boolean isManager()    { return roles.contains("MANAGER"); }
    public boolean isTechnician() { return roles.contains("TECHNICIAN"); }
    public boolean isCustomer()   { return roles.contains("CUSTOMER"); }

    /**
     * Returns {@code true} when the principal's roles grant tenant-wide (unrestricted)
     * access. ADMIN, DISPATCHER, and MANAGER all read across the full tenant.
     */
    public boolean isPermitAll() {
        return isAdmin() || isDispatcher() || isManager();
    }

    @Override
    public String toString() {
        // Never include actual IDs or account sets in toString — safe for logging
        return "AccessScope[userId=<redacted>, roles=" + roles + ", hasTechnicianId=" + (technicianId != null)
                + ", customerAccountCount=" + customerAccountIds.size() + "]";
    }
}
