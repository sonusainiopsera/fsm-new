package com.fieldservice.platform.persistence;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeContext;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The single entry-point for all reads over {@link ScopedEntity} types.
 *
 * <p>Every method ANDs the caller's {@link AccessScope} predicate (obtained
 * from the request-scoped {@link AccessScopeContext}) into the provided
 * {@link Specification} <em>before</em> delegating to the repository. This
 * ensures that out-of-scope rows are never loaded — not fetched then discarded —
 * so they cannot leak through counts, totals, error messages, or logs.
 *
 * <h3>Pagination and count queries</h3>
 * {@link #findAll(JpaSpecificationExecutor, Specification, Pageable, Class)} uses
 * Spring Data's {@code JpaSpecificationExecutor.findAll(Specification, Pageable)},
 * which applies the same {@code Specification} to both the data query and the
 * count query that populates {@code Page.totalElements}. The scope predicate is
 * therefore present in both, so {@code totalElements} never reveals out-of-scope rows.
 *
 * <h3>Single-entity fetch</h3>
 * {@link #requireById(JpaSpecificationExecutor, Object, Class)} uses
 * {@code findOne(Specification)} with the scope predicate ANDed with the id
 * predicate. An out-of-scope row and a nonexistent id both produce an empty
 * {@code Optional}, which is converted to a {@link ScopedAccessDeniedException}
 * — giving byte-identical 403 responses in both cases (existence non-disclosure).
 *
 * <h3>Denial logging</h3>
 * On denial the executor logs at WARN with actor info (from AccessScope),
 * entity type, and a hash of the requested id, but never the id itself.
 */
@Component
public class ScopedQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScopedQueryExecutor.class);

    private final AccessScopePredicateFactory predicateFactory;
    private final AccessScopeContext scopeContext;    // proxied @RequestScope bean

    public ScopedQueryExecutor(AccessScopePredicateFactory predicateFactory,
                                AccessScopeContext scopeContext) {
        this.predicateFactory = predicateFactory;
        this.scopeContext = scopeContext;
    }

    /**
     * List entities of {@code entityType} matching {@code additionalSpec}, restricted
     * to the caller's row scope. Both the data and count queries include the scope
     * predicate.
     *
     * @param executor       the scoped repository for this entity type
     * @param additionalSpec extra filter conditions (may be {@code null})
     * @param pageable       pagination and sort
     * @param entityType     runtime type of T, needed to look up the scope factory
     * @param <T>            entity type
     * @return scoped, paginated result set
     */
    public <T> Page<T> findAll(JpaSpecificationExecutor<T> executor,
                                @Nullable Specification<T> additionalSpec,
                                Pageable pageable,
                                Class<T> entityType) {
        Specification<T> scopeSpec = scopeSpec(entityType);
        Specification<T> combined = additionalSpec != null
                ? scopeSpec.and(additionalSpec)
                : scopeSpec;
        return executor.findAll(combined, pageable);
    }

    /**
     * List all entities of {@code entityType} matching {@code additionalSpec} in the
     * caller's row scope (no pagination).
     */
    public <T> List<T> findAll(JpaSpecificationExecutor<T> executor,
                                @Nullable Specification<T> additionalSpec,
                                Class<T> entityType) {
        Specification<T> scopeSpec = scopeSpec(entityType);
        Specification<T> combined = additionalSpec != null
                ? scopeSpec.and(additionalSpec)
                : scopeSpec;
        return executor.findAll(combined);
    }

    /**
     * Find a single entity of {@code entityType} matching {@code spec}, restricted
     * to the caller's row scope.
     *
     * @return the matching entity wrapped in an {@link Optional}, or empty if the
     *         entity does not exist or is outside the caller's scope
     */
    public <T> Optional<T> findOne(JpaSpecificationExecutor<T> executor,
                                    Specification<T> spec,
                                    Class<T> entityType) {
        Specification<T> combined = scopeSpec(entityType).and(spec);
        return executor.findOne(combined);
    }

    /**
     * Fetch entity {@code id} of type {@code entityType}. If the record does not exist
     * or is outside the caller's scope, logs a WARN and throws
     * {@link ScopedAccessDeniedException} — never 404.
     *
     * <p>The two failure cases are indistinguishable to the caller to prevent
     * existence-disclosure (documented in {@code platform/api/package-info.java}).
     *
     * @throws ScopedAccessDeniedException if the record is absent or out-of-scope
     */
    public <T, ID> T requireById(JpaSpecificationExecutor<T> executor,
                                  ID id,
                                  Class<T> entityType) {
        Specification<T> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<T> combined = scopeSpec(entityType).and(idSpec);

        return executor.findOne(combined).orElseThrow(() -> {
            AccessScope scope = scopeContext.get();
            String idHash = String.format("%08x", id.hashCode());
            log.warn("Scoped access denied: entityType={}, idHash=0x{}, userId=<redacted>, roles={}",
                    entityType.getSimpleName(), idHash, scope.roles());
            return new ScopedAccessDeniedException(
                    "Resource of type " + entityType.getSimpleName() + " not accessible");
        });
    }

    private <T> Specification<T> scopeSpec(Class<T> entityType) {
        AccessScope scope = scopeContext.get();
        return predicateFactory.specificationFor(entityType, scope);
    }
}
