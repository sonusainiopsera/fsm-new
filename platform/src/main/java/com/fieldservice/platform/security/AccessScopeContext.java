package com.fieldservice.platform.security;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Request-scoped holder for the current {@link AccessScope}. The scope is resolved
 * lazily on first access and cached for the duration of the request so the
 * SecurityContext is read exactly once per request.
 *
 * <p>Injected as a CGLIB proxy into singleton beans (e.g. {@link com.fieldservice.platform.persistence.ScopedQueryExecutor})
 * so each call is dispatched to the correct request-bound instance.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class AccessScopeContext {

    private AccessScope cached;

    /**
     * Returns the resolved {@link AccessScope} for the current request.
     *
     * @throws ScopedAccessDeniedException if there is no JWT authentication or
     *                                     the token has no {@code roles} claim.
     */
    public AccessScope get() {
        if (cached == null) {
            cached = resolve();
        }
        return cached;
    }

    private AccessScope resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ScopedAccessDeniedException("No JWT authentication present");
        }
        Jwt jwt = jwtAuth.getToken();

        String userId = jwt.getSubject();

        List<String> rolesClaim = jwt.getClaimAsStringList("roles");
        if (rolesClaim == null || rolesClaim.isEmpty()) {
            throw new ScopedAccessDeniedException(
                    "JWT missing or empty 'roles' claim — access denied by policy");
        }
        Set<String> roles = new HashSet<>(rolesClaim);

        String technicianId = jwt.getClaimAsString("technician_id");

        List<String> accountIdStrings = jwt.getClaimAsStringList("customer_account_ids");
        Set<UUID> customerAccountIds;
        try {
            customerAccountIds = accountIdStrings != null
                    ? accountIdStrings.stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet())
                    : Collections.emptySet();
        } catch (IllegalArgumentException e) {
            throw new ScopedAccessDeniedException("Malformed customer_account_ids claim in JWT");
        }

        return new AccessScope(userId, roles, technicianId, customerAccountIds);
    }
}
