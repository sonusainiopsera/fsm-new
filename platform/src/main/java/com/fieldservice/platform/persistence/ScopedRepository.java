package com.fieldservice.platform.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository interface for scoped entities.
 *
 * <p>All domain repositories for entities that implement {@link ScopedEntity} must extend
 * this interface rather than {@link JpaRepository} or {@link JpaSpecificationExecutor} directly.
 * The presence of {@link JpaSpecificationExecutor} on this interface enables the
 * {@link ScopedQueryExecutor} to compose the mandatory access-scope predicate into every query.
 *
 * <p><strong>Access policy:</strong> Domain code must not call {@code findById}, {@code findAll},
 * or any other read method on this repository directly. All reads must route through
 * {@link ScopedQueryExecutor}, which guarantees the scope predicate is applied before
 * the query reaches the database. Direct calls bypass row-scope enforcement.
 *
 * <p>Write operations ({@code save}, {@code delete}) do not require scope mediation because
 * they are guarded by service-layer method security annotations.
 *
 * @param <T>  the entity type, which must implement {@link ScopedEntity}
 * @param <ID> the entity identifier type
 *
 * @see ScopedQueryExecutor
 * @see com.fieldservice.platform.security.AccessScopePredicateFactory
 */
@NoRepositoryBean
public interface ScopedRepository<T extends ScopedEntity, ID>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

    // No additional methods — the interface exists to enforce the type constraint
    // and signal to the ScopedQueryExecutor that this repository may be used for
    // scoped reads.
}
