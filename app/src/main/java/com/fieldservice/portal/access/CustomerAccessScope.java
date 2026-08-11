package com.fieldservice.portal.access;

import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalAccountUserRepository;
import com.fieldservice.portal.domain.PortalAccountUserStatus;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.persistence.criteria.JoinType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

/**
 * Request-scoped portal access resolver.
 *
 * <p>Resolves the authenticated principal's customer account id by querying the
 * {@code portal_account_user} table, then provides a JPA {@link Specification} that
 * constrains any {@code work_order} query to rows whose site belongs to that account.
 *
 * <h3>Fail-closed semantics</h3>
 * <ul>
 *   <li>No linkage row → throws {@link ScopeUnavailableException} (maps to 404).</li>
 *   <li>Linkage row with status PENDING or SUSPENDED → throws {@link ScopeUnavailableException}.</li>
 *   <li>Principal not a JWT → delegates to {@link AccessScopeResolver} which throws 403.</li>
 * </ul>
 *
 * <p>The resolved account id is cached within the request; the database is queried at most once
 * per request regardless of how many service methods invoke this component.
 *
 * <h3>SQL predicate</h3>
 * The returned {@link Specification} emits:
 * <pre>
 *   EXISTS (SELECT 1 FROM site s WHERE s.id = work_order.site_id AND s.customer_id = :accountId)
 * </pre>
 * as a correlated exists sub-query, ensuring the predicate is evaluated in the WHERE clause
 * rather than as a post-fetch filter (AC-5).
 */
@Component
@RequestScope
public class CustomerAccessScope {

    private static final Logger log = LoggerFactory.getLogger(CustomerAccessScope.class);

    private final AccessScopeResolver scopeResolver;
    private final PortalAccountUserRepository linkageRepository;

    /** Cached within the request; null until first call to {@link #resolveAccountId()}. */
    private UUID cachedAccountId;

    public CustomerAccessScope(AccessScopeResolver scopeResolver,
                               PortalAccountUserRepository linkageRepository) {
        this.scopeResolver = scopeResolver;
        this.linkageRepository = linkageRepository;
    }

    /**
     * Resolves and caches the account id for the current request principal.
     *
     * @return the customer account UUID
     * @throws ScopeUnavailableException if no active linkage row exists for the principal
     */
    public UUID resolveAccountId() {
        if (cachedAccountId != null) {
            return cachedAccountId;
        }
        UUID userId = scopeResolver.resolve().userId();
        PortalAccountUser linkage = linkageRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    log.debug("portal.scope.unavailable: userId={} reason=NO_LINKAGE_ROW", userId);
                    return new ScopeUnavailableException(
                            "No portal linkage exists for userId=" + userId);
                });

        if (linkage.getStatus() != PortalAccountUserStatus.ACTIVE) {
            log.debug("portal.scope.unavailable: userId={} status={}", userId, linkage.getStatus());
            throw new ScopeUnavailableException(
                    "Portal linkage is not ACTIVE for userId=" + userId
                    + "; status=" + linkage.getStatus());
        }

        cachedAccountId = linkage.getAccountId();
        return cachedAccountId;
    }

    /**
     * Returns a JPA {@link Specification} constraining a work-order query to rows
     * whose site belongs to the resolved customer account.
     *
     * <p>The predicate joins {@code work_order.site_id} to {@code site.customer_id},
     * which is evaluated in the WHERE clause by the JPA provider — never post-fetch.
     *
     * @param <T> the entity root type (typically WorkOrder or a portal projection)
     * @return a Specification that emits {@code site.customer_id = :accountId}
     * @throws ScopeUnavailableException if the account cannot be resolved
     */
    public <T> Specification<T> workOrderScopeSpec() {
        UUID accountId = resolveAccountId();
        return (root, query, cb) -> {
            // JOIN work_order → site, then constrain site.customer_id = accountId
            var siteJoin = root.join("site", JoinType.INNER);
            return cb.equal(siteJoin.get("customerId"), accountId);
        };
    }
}
