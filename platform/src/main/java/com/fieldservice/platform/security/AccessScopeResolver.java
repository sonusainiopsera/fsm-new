package com.fieldservice.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the {@link AccessScope} for the current request exactly once from the authenticated JWT.
 *
 * <p>This bean is {@link RequestScope request-scoped}: a fresh instance is created per request
 * and the resolved scope is cached within the request so the SecurityContext is consulted only
 * once, regardless of how many repository calls compose scope predicates.
 *
 * <p>Failure semantics: any resolution failure (missing token, missing roles claim,
 * malformed claim values) throws {@link ScopedAccessDeniedException}, which the global handler
 * maps to a uniform 403. Authorization never fails open.
 *
 * <p>JWT claim conventions:
 * <ul>
 *   <li>{@code sub} — UUID of the authenticated user</li>
 *   <li>{@code roles} — list of role strings (with or without {@code ROLE_} prefix)</li>
 *   <li>{@code technicianId} — UUID of the technician profile (TECHNICIAN role only)</li>
 *   <li>{@code customerAccountIds} — list of customer account UUIDs (CUSTOMER role only)</li>
 * </ul>
 */
@Component
@RequestScope
public class AccessScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(AccessScopeResolver.class);

    private AccessScope cached;

    /**
     * Resolves and caches the access scope for the current request.
     *
     * @return the resolved {@link AccessScope}
     * @throws ScopedAccessDeniedException if the request cannot be attributed to a valid scope
     */
    public AccessScope resolve() {
        if (cached != null) {
            return cached;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: unauthenticated request");
        }

        if (!(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: principal is not a JWT");
        }

        UUID userId = resolveUserId(jwt);
        Set<String> roles = resolveRoles(jwt);
        UUID technicianId = resolveTechnicianId(jwt, roles);
        Set<UUID> customerAccountIds = resolveCustomerAccountIds(jwt, roles);

        cached = new AccessScope(userId, roles, technicianId, customerAccountIds);
        log.debug("Resolved access scope: userId={}, roles={}", userId, roles);
        return cached;
    }

    private UUID resolveUserId(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: missing sub claim");
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: sub claim is not a valid UUID");
        }
    }

    private Set<String> resolveRoles(Jwt jwt) {
        Collection<String> rolesClaim = jwt.getClaimAsStringList("roles");
        if (rolesClaim == null || rolesClaim.isEmpty()) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: missing or empty roles claim; access denied");
        }

        Set<String> roles = rolesClaim.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .collect(Collectors.toUnmodifiableSet());

        if (roles.isEmpty()) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: roles claim contained no valid entries");
        }

        return roles;
    }

    private UUID resolveTechnicianId(Jwt jwt, Set<String> roles) {
        if (!roles.contains(Role.TECHNICIAN)) {
            return null;
        }
        String techIdClaim = jwt.getClaimAsString("technicianId");
        if (techIdClaim == null || techIdClaim.isBlank()) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: TECHNICIAN principal missing technicianId claim");
        }
        try {
            return UUID.fromString(techIdClaim);
        } catch (IllegalArgumentException e) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: technicianId claim is not a valid UUID");
        }
    }

    private Set<UUID> resolveCustomerAccountIds(Jwt jwt, Set<String> roles) {
        if (!roles.contains(Role.CUSTOMER)) {
            return Set.of();
        }
        List<String> accountsClaim = jwt.getClaimAsStringList("customerAccountIds");
        if (accountsClaim == null || accountsClaim.isEmpty()) {
            // A customer with no linked accounts sees nothing; this is not an error.
            return Set.of();
        }
        try {
            return accountsClaim.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException e) {
            throw new ScopedAccessDeniedException(
                    "Unable to resolve access scope: customerAccountIds claim contains a non-UUID value");
        }
    }
}
