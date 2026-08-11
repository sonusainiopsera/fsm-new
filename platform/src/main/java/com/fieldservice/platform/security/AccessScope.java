package com.fieldservice.platform.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable value object representing the access scope of the authenticated principal
 * for the current request.
 * <p>
 * Resolved exactly once per request by {@link AccessScopeResolver} from the validated JWT,
 * then injected wherever row-scope predicates need to be applied.
 *
 * <p>Design note: This record carries only the identifiers needed to construct JPA Specification
 * predicates; it never holds PII, raw claims, or mutable state. The {@code customerAccountIds}
 * field intentionally captures the full union so a customer user linked to two accounts sees
 * exactly those two accounts and nothing beyond.
 *
 * @param userId              The UUID of the authenticated user (JWT {@code sub} claim).
 * @param roles               The set of granted role strings (ROLE_-prefixed) from the JWT
 *                            {@code roles} claim.
 * @param technicianId        The technician identifier from the JWT {@code technicianId} claim;
 *                            {@code null} for non-technician principals.
 * @param customerAccountIds  The set of customer account UUIDs from the JWT
 *                            {@code customerAccountIds} claim; empty for non-customer principals.
 */
public record AccessScope(
        UUID userId,
        Set<String> roles,
        UUID technicianId,
        Set<UUID> customerAccountIds
) {

    /** Compact constructor — enforces non-null invariants and defensive copies. */
    public AccessScope {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(customerAccountIds, "customerAccountIds must not be null");
        roles = Set.copyOf(roles);
        customerAccountIds = Set.copyOf(customerAccountIds);
    }

    /**
     * Returns {@code true} if this scope carries the given role.
     * Accepts both prefixed ({@code "ROLE_DISPATCHER"}) and bare ({@code "DISPATCHER"}) strings.
     */
    public boolean hasRole(String role) {
        String normalised = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return roles.contains(normalised);
    }

    /**
     * Returns {@code true} if this principal has a privileged role (DISPATCHER, ADMIN, or MANAGER)
     * that receives a permit-all predicate rather than a row-scoped predicate.
     */
    public boolean isPrivileged() {
        return roles.stream().anyMatch(Role.PRIVILEGED_ROLES::contains);
    }

    /**
     * Returns {@code true} if this principal is a TECHNICIAN.
     */
    public boolean isTechnician() {
        return hasRole(Role.TECHNICIAN);
    }

    /**
     * Returns {@code true} if this principal is a CUSTOMER portal user.
     */
    public boolean isCustomer() {
        return hasRole(Role.CUSTOMER);
    }
}
