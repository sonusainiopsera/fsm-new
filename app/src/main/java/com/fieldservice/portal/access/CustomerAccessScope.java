package com.fieldservice.portal.access;

import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.repository.PortalAccountUserRepository;
import com.fieldservice.workorder.domain.WorkOrder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

/**
 * Request-scoped bean that resolves the authenticated portal principal's customer account id
 * from the {@code portal_account_user} table and produces a JPA {@link Specification}
 * restricting {@link WorkOrder} queries to that account's sites.
 *
 * <h3>Fail-closed guarantee</h3>
 * If the principal is not authenticated, not a JWT, or has no active
 * {@code portal_account_user} row, {@link #resolveAccountId()} throws
 * {@link ScopeUnavailableException}. The account id is never null when successfully
 * resolved, and the predicate is never unrestricted.
 *
 * <h3>Per-request caching</h3>
 * The {@code @RequestScope} means one instance per HTTP request. The account id is
 * resolved at most once and cached in {@link #cachedAccountId} for the lifetime of the
 * request. Concurrent calls within the same request context are safe because Spring's
 * request scope is single-threaded.
 *
 * <h3>Disclosure rule</h3>
 * Out-of-scope work orders are indistinguishable from non-existent ones: both conditions
 * produce an identical 404 response via {@link com.fieldservice.portal.web.PortalExceptionAdvice}.
 */
@Component
@RequestScope
public class CustomerAccessScope {

    private final PortalAccountUserRepository portalAccountUserRepository;

    private UUID cachedAccountId;

    public CustomerAccessScope(PortalAccountUserRepository portalAccountUserRepository) {
        this.portalAccountUserRepository = portalAccountUserRepository;
    }

    /**
     * Resolves and caches the authenticated principal's customer account id.
     *
     * @return the UUID of the customer account the principal is linked to
     * @throws ScopeUnavailableException if the principal is not authenticated,
     *         not a JWT, or has no active portal account user row
     */
    public UUID resolveAccountId() {
        if (cachedAccountId != null) {
            return cachedAccountId;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ScopeUnavailableException("Unauthenticated request");
        }
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new ScopeUnavailableException("Unsupported authentication type");
        }

        String subject = jwtAuth.getToken().getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ScopeUnavailableException("Missing JWT subject claim");
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new ScopeUnavailableException("Malformed JWT subject: not a valid UUID");
        }

        PortalAccountUser linkage = portalAccountUserRepository.findByUserId(userId)
                .orElseThrow(() -> new ScopeUnavailableException(
                        "No portal account linkage for user"));

        if (!linkage.isActive()) {
            throw new ScopeUnavailableException("Portal account linkage is not active");
        }

        cachedAccountId = linkage.getAccountId();
        return cachedAccountId;
    }

    /**
     * Returns a JPA {@link Specification} that restricts {@link WorkOrder} results to
     * those belonging to the authenticated customer's account. The predicate joins
     * work_order → site and constrains {@code site.customer_id}.
     *
     * @throws ScopeUnavailableException (via {@link #resolveAccountId()}) if the principal
     *         has no valid portal linkage
     */
    public Specification<WorkOrder> workOrderPredicate() {
        UUID accountId = resolveAccountId();
        return (root, query, cb) -> {
            Join<Object, Object> siteJoin = root.join("site", JoinType.INNER);
            return cb.equal(siteJoin.get("customerId"), accountId);
        };
    }
}
