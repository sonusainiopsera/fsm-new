package com.fieldservice.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable value object capturing the authorization scope of the authenticated principal
 * for a single request. Resolved once per request by {@link AccessScopeResolver} from the
 * verified JWT and cached in a request-scoped bean; never re-read from the SecurityContext
 * inside repository or persistence code.
 *
 * <p>Roles are stored as raw claim values (e.g. "DISPATCHER"), without the Spring Security
 * "ROLE_" prefix, to keep domain code independent of the security framework.
 *
 * <p>BR-19 / OWASP A01: every field is final and the record is immutable. Absence of a
 * required claim (e.g. technician_id for a TECHNICIAN principal) results in an empty/null
 * value that the predicate factory treats as deny-all, never as permit-all.
 */
public record AccessScope(
        UUID userId,
        Set<String> roles,
        UUID technicianId,
        Set<UUID> customerAccountIds) {

    /** Canonical empty customer account set — avoids null checks in predicate code. */
    public static final Set<UUID> NO_ACCOUNTS = Set.of();

    /**
     * Compact constructor: defensively copy mutable sets to guarantee immutability.
     * A null roles claim is collapsed to empty, giving deny-by-default behaviour.
     */
    public AccessScope {
        roles = (roles == null) ? Set.of() : Set.copyOf(roles);
        customerAccountIds = (customerAccountIds == null) ? NO_ACCOUNTS : Set.copyOf(customerAccountIds);
    }

    /** Returns true if this scope carries the given raw role name (e.g. "DISPATCHER"). */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * DISPATCHER, ADMIN, and MANAGER all receive team-wide (permit-all) row scope —
     * they may read every row of any scoped entity.
     */
    public boolean isPrivileged() {
        return hasRole("DISPATCHER") || hasRole("ADMIN") || hasRole("MANAGER");
    }

    /** Returns true when the principal holds the TECHNICIAN role. */
    public boolean isTechnician() {
        return hasRole("TECHNICIAN");
    }

    /** Returns true when the principal holds the CUSTOMER role. */
    public boolean isCustomer() {
        return hasRole("CUSTOMER");
    }

    /**
     * Returns true when the scope is effectively empty — no roles — which means every
     * scoped read must be denied.
     */
    public boolean isEmpty() {
        return roles.isEmpty();
    }
}
