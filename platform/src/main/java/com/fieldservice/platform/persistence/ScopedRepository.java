package com.fieldservice.platform.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository type for every scoped entity.
 *
 * <p>Domain repositories <strong>must</strong> extend this interface instead of
 * {@link JpaRepository} directly when their entity implements {@link ScopedEntity}. This
 * constraint makes it structurally impossible to issue an unscoped read: all reads over
 * scoped entities must flow through {@link ScopedQueryExecutor}, which composes the
 * {@link com.fieldservice.platform.security.AccessScope} predicate before delegating to
 * {@link JpaSpecificationExecutor}.
 *
 * <p>The {@link NoRepositoryBean} annotation prevents Spring Data from creating a proxy
 * for this interface itself — it is only a structural constraint enforced at the type
 * level.
 *
 * @param <T>  the scoped entity type
 * @param <ID> the primary-key type
 */
@NoRepositoryBean
public interface ScopedRepository<T extends ScopedEntity, ID>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {
    // Inherits full CRUD from JpaRepository and Specification-based query from
    // JpaSpecificationExecutor. ScopedQueryExecutor is the only permitted caller of the
    // Specification-accepting methods; direct repository access from controllers or
    // services is forbidden by ArchUnit fitness tests.
}
