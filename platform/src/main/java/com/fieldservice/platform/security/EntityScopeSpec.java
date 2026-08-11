package com.fieldservice.platform.security;

import com.fieldservice.platform.persistence.ScopedEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * Strategy interface that produces a JPA {@link Specification} for a specific scoped entity
 * type given the acting principal's {@link AccessScope}.
 *
 * <p>Each scoped entity module (work-order, site, etc.) provides exactly one
 * {@code @Component} implementation of this interface. The
 * {@link AccessScopePredicateFactory} collects all implementations on startup and fails
 * fast if any registered scoped entity type lacks a corresponding spec.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Must never return {@code null}; return a deny-all predicate
 *       ({@code cb.disjunction()}) when the scope does not grant access.</li>
 *   <li>Must be side-effect-free and stateless; the same instance is reused across
 *       requests.</li>
 *   <li>The returned Specification must translate entirely into SQL WHERE-clause
 *       predicates — no post-fetch filtering in Java is permitted.</li>
 * </ul>
 *
 * @param <T> the scoped entity type
 */
public interface EntityScopeSpec<T extends ScopedEntity> {

    /** Returns the entity class this spec covers. */
    Class<T> entityType();

    /**
     * Builds the row-scope predicate for the given principal scope.
     * The returned {@link Specification} is AND-composed with any caller-supplied filter
     * by {@link com.fieldservice.platform.persistence.ScopedQueryExecutor} before the
     * query is executed.
     *
     * @param scope the resolved scope for the current request — never {@code null}
     * @return a non-null Specification; deny-all if the scope grants no access
     */
    Specification<T> specFor(AccessScope scope);
}
