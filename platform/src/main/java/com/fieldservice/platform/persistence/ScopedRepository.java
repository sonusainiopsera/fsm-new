package com.fieldservice.platform.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository type for all scoped entities. Extends both
 * {@link JpaRepository} (for write operations) and
 * {@link JpaSpecificationExecutor} (for the Specification-based reads that
 * {@link ScopedQueryExecutor} requires).
 *
 * <h3>Important: use ScopedQueryExecutor for all reads</h3>
 * Never call {@code findById}, {@code findAll}, or other read methods directly on
 * a {@code ScopedRepository}. All reads over scoped entities must go through
 * {@link ScopedQueryExecutor} so the caller's row-scope predicate is always ANDed
 * into the query. The ArchUnit fitness tests enforce this rule at build time.
 *
 * <p>Direct write operations ({@code save}, {@code delete}) are intentionally
 * accessible from service code.
 */
@NoRepositoryBean
public interface ScopedRepository<T extends ScopedEntity, ID>
        extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {
}
