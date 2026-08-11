package com.fieldservice.platform.security;

/**
 * Role constants for the field service platform.
 * <p>
 * Roles are prefixed with {@code ROLE_} to integrate with Spring Security's
 * granted authority convention. These constants correspond to the {@code roles}
 * claim in the JWT access token.
 * <p>
 * Role semantics for row-scope enforcement:
 * <ul>
 *   <li>DISPATCHER — privileged; sees all work orders and resources team-wide</li>
 *   <li>ADMIN — privileged; sees all work orders and resources team-wide</li>
 *   <li>MANAGER — privileged; read-only team-wide access</li>
 *   <li>TECHNICIAN — scoped; sees only work orders where they are the current assignee</li>
 *   <li>CUSTOMER — scoped; sees only work orders for sites belonging to their customer accounts</li>
 * </ul>
 */
public final class Role {

    private Role() {
        // Utility class — no instantiation
    }

    /** Full stack dispatcher. Privileged — permit-all predicate. */
    public static final String DISPATCHER = "ROLE_DISPATCHER";

    /** System administrator. Privileged — permit-all predicate. */
    public static final String ADMIN = "ROLE_ADMIN";

    /** Operations manager. Privileged — permit-all predicate (read-oriented). */
    public static final String MANAGER = "ROLE_MANAGER";

    /**
     * Field technician. Scoped to work orders where {@code assigned_technician_id} equals
     * the technician's own id resolved from the {@code technicianId} JWT claim.
     */
    public static final String TECHNICIAN = "ROLE_TECHNICIAN";

    /**
     * Customer portal user. Scoped to work orders whose site belongs to one of the
     * customer accounts listed in the {@code customerAccountIds} JWT claim.
     */
    public static final String CUSTOMER = "ROLE_CUSTOMER";

    /** All privileged role names (permit-all predicate). */
    public static final java.util.Set<String> PRIVILEGED_ROLES =
            java.util.Set.of(DISPATCHER, ADMIN, MANAGER);
}
