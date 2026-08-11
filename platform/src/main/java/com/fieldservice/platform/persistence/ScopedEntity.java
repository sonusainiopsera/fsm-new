package com.fieldservice.platform.persistence;

/**
 * Marker interface for JPA entities whose reads must carry a mandatory row-scope predicate
 * derived from the authenticated principal's {@link com.fieldservice.platform.security.AccessScope}.
 *
 * <p>Entities that implement this interface are subject to the following invariants enforced
 * by {@link ScopedQueryExecutor}:
 * <ol>
 *   <li>All collection reads (list, page, count) compose the scope {@link
 *       org.springframework.data.jpa.domain.Specification} into the WHERE clause before
 *       execution — no post-fetch filtering in Java.</li>
 *   <li>Single-entity fetches use {@code findOne(idSpec.and(scopeSpec))} so that an
 *       out-of-scope row is never loaded at all.</li>
 *   <li>Out-of-scope and nonexistent identifiers produce identical
 *       {@link com.fieldservice.platform.security.ScopedAccessDeniedException}s, mapped to
 *       HTTP 403, so a caller cannot distinguish absence from denial.</li>
 * </ol>
 *
 * <p>Scoped entities: {@code work_order}, {@code assignment}, {@code site}, {@code asset},
 * {@code stock_movement}.
 *
 * <p>Every entity implementing this interface must have a corresponding
 * {@link com.fieldservice.platform.security.EntityScopeSpec} bean registered with the
 * {@link com.fieldservice.platform.security.AccessScopePredicateFactory} — the context
 * will fail to start if any is missing.
 */
public interface ScopedEntity {
    // Marker interface — no methods required.
}
