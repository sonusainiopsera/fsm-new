package com.fieldservice.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves an {@link AccessScope} from a verified {@link JwtAuthenticationToken} once per
 * request. Callers should obtain the resolved scope via the request-scoped
 * {@link RequestScopedAccessScope} provider rather than calling this resolver directly; the
 * provider caches the result so the JWT is parsed at most once per request.
 *
 * <p>Claim mapping:
 * <ul>
 *   <li>{@code sub}                  → {@code userId} (UUID)</li>
 *   <li>{@code roles}                → {@code roles} (Set&lt;String&gt;)</li>
 *   <li>{@code technician_id}        → {@code technicianId} (UUID, nullable)</li>
 *   <li>{@code customer_account_ids} → {@code customerAccountIds} (Set&lt;UUID&gt;)</li>
 * </ul>
 *
 * <p>Any missing, malformed, or unrecognised claim degrades gracefully to null / empty,
 * which the {@link AccessScopePredicateFactory} then treats as deny-all. Resolution never
 * fails open.
 */
@Component
public class AccessScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(AccessScopeResolver.class);

    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_TECHNICIAN_ID = "technician_id";
    static final String CLAIM_CUSTOMER_ACCOUNT_IDS = "customer_account_ids";

    /**
     * Resolves the access scope from the given authentication object.
     *
     * @throws ScopedAccessDeniedException if the authentication is null, not a JWT, or
     *                                     carries an unresolvable principal
     */
    public AccessScope resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ScopedAccessDeniedException("Unauthenticated request");
        }

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            log.warn("scope_resolution_failed auth_type={}", authentication.getClass().getSimpleName());
            throw new ScopedAccessDeniedException("Unsupported authentication type");
        }

        Jwt jwt = jwtAuth.getToken();

        UUID userId = parseUuid(jwt.getSubject(), "sub");
        Set<String> roles = extractRoles(jwt);
        UUID technicianId = extractUuid(jwt, CLAIM_TECHNICIAN_ID);
        Set<UUID> customerAccountIds = extractUuidSet(jwt, CLAIM_CUSTOMER_ACCOUNT_IDS);

        return new AccessScope(userId, roles, technicianId, customerAccountIds);
    }

    private Set<String> extractRoles(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        // Strip ROLE_ prefix if the issuer already added it, normalise to plain name.
        return raw.stream()
                .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                .collect(Collectors.toUnmodifiableSet());
    }

    private UUID extractUuid(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("scope_claim_invalid claim={} reason=invalid_uuid", claim);
            return null;
        }
    }

    private Set<UUID> extractUuidSet(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        if (values == null || values.isEmpty()) {
            return AccessScope.NO_ACCOUNTS;
        }
        return values.stream()
                .map(v -> {
                    try {
                        return UUID.fromString(v);
                    } catch (IllegalArgumentException e) {
                        log.warn("scope_claim_invalid claim={} reason=invalid_uuid value_omitted=true", claim);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ScopedAccessDeniedException("Missing required JWT claim: " + fieldName);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ScopedAccessDeniedException("Malformed JWT claim: " + fieldName);
        }
    }
}
