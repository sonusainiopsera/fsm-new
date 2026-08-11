package com.fieldservice.platform.persistence;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single gateway for all reads over {@link ScopedEntity} types.
 *
 * <p>Every method composes the caller-supplied filter (if any) with the mandatory
 * {@link com.fieldservice.platform.security.AccessScope} predicate <em>before</em>
 * delegating to {@link ScopedRepository}. The scope predicate is evaluated entirely in
 * SQL — no post-fetch filtering in Java — so counts, totals, and pagination
 * {@code totalElements} all reflect only rows the principal is authorised to see.
 *
 * <h3>Non-disclosure for single-entity fetches</h3>
 * {@link #findById} ANDs the id constraint with the scope predicate and passes them to
 * {@code findOne}, which returns {@link Optional#empty()} for <em>both</em>:
 * <ul>
 *   <li>the entity does not exist, and</li>
 *   <li>the entity exists but is outside the caller's scope.</li>
 * </ul>
 * Callers convert {@link Optional#empty()} to a
 * {@link ScopedAccessDeniedException} — never a 404 — so the two cases are
 * indistinguishable to the client.
 *
 * <h3>Pagination</h3>
 * {@link #findAll(ScopedRepository, Specification, Pageable, AccessScope, Class)} delegates
 * to {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(
 * Specification, Pageable)}, which applies the same specification to both the row query and
 * the count query, ensuring {@code totalElements} never reveals out-of-scope rows.
 */
@Component
public class ScopedQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScopedQueryExecutor.class);

    private final AccessScopePredicateFactory predicateFactory;

    public ScopedQueryExecutor(AccessScopePredicateFactory predicateFactory) {
        this.predicateFactory = predicateFactory;
    }

    /**
     * Finds a single entity by id within the caller's scope.
     *
     * <p>Returns {@link Optional#empty()} when the entity does not exist OR is outside the
     * scope — the two cases are deliberately indistinguishable to prevent existence
     * disclosure.
     *
     * @param repository  the scoped repository
     * @param id          the entity identifier
     * @param scope       the resolved scope for the current request
     * @param entityClass the entity class (needed to look up the scope predicate)
     * @param <T>         the scoped entity type
     * @param <ID>        the primary-key type
     * @return an {@link Optional} containing the entity if it exists within scope,
     *         otherwise empty
     */
    public <T extends ScopedEntity, ID> Optional<T> findById(
            ScopedRepository<T, ID> repository,
            ID id,
            AccessScope scope,
            Class<T> entityClass) {

        Specification<T> scopeSpec = predicateFactory.specFor(entityClass, scope);
        Specification<T> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<T> combined = Specification.allOf(idSpec, scopeSpec);

        Optional<T> result = repository.findOne(combined);
        if (result.isEmpty()) {
            log.warn("scope_access_denied entity={} user_id={}",
                    entityClass.getSimpleName(), scope.userId());
        }
        return result;
    }

    /**
     * Returns a page of entities matching both the given filter and the scope predicate.
     *
     * <p>The count query that populates {@code Page.totalElements} uses the same combined
     * specification, so the reported total never reveals out-of-scope rows.
     *
     * @param repository  the scoped repository
     * @param filter      an additional caller-supplied filter, or {@code null} for
     *                    "no extra filter"
     * @param pageable    pagination parameters
     * @param scope       the resolved scope for the current request
     * @param entityClass the entity class
     * @param <T>         the scoped entity type
     * @return a page of entities visible to the caller
     */
    public <T extends ScopedEntity> Page<T> findAll(
            ScopedRepository<T, ?> repository,
            Specification<T> filter,
            Pageable pageable,
            AccessScope scope,
            Class<T> entityClass) {

        Specification<T> scopeSpec = predicateFactory.specFor(entityClass, scope);
        Specification<T> combined = (filter == null)
                ? scopeSpec
                : Specification.allOf(filter, scopeSpec);

        return repository.findAll(combined, pageable);
    }

    /**
     * Convenience overload for listing without an additional filter.
     */
    public <T extends ScopedEntity> Page<T> findAll(
            ScopedRepository<T, ?> repository,
            Pageable pageable,
            AccessScope scope,
            Class<T> entityClass) {
        return findAll(repository, null, pageable, scope, entityClass);
    }
}
