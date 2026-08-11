package com.fieldservice.platform.persistence;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The single gateway for all reads over {@link ScopedEntity} types.
 *
 * <p>Every read — list, page, or single-entity fetch — is routed through this executor,
 * which retrieves the current request's {@link AccessScope} and composes the entity-specific
 * scope predicate from the {@link AccessScopePredicateFactory} before delegating to the
 * underlying {@link ScopedRepository}.
 *
 * <p>This makes it structurally impossible to issue an unscoped read: the factory always
 * ANDs the scope {@link Specification} before execution.
 *
 * <p><strong>Non-disclosure contract:</strong> {@link #findById} uses
 * {@link ScopedRepository#findOne(Specification)} with the composed predicate.
 * An out-of-scope row is never loaded — so it cannot be distinguished from a nonexistent row —
 * and both cases result in a {@link ScopedAccessDeniedException} that maps to a uniform 403.
 * Domain code must not convert that exception to a 404.
 *
 * <p><strong>Pagination:</strong> {@link #findAll(Class, Specification, Pageable, ScopedRepository)}
 * delegates to {@link ScopedRepository#findAll(Specification, Pageable)}, which runs both the
 * data query and the {@code COUNT} query with the composed predicate. This guarantees
 * {@code totalElements} never reveals rows the caller cannot see.
 */
@Service
public class ScopedQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScopedQueryExecutor.class);

    private final AccessScopePredicateFactory predicateFactory;
    private final AccessScopeResolver scopeResolver;

    public ScopedQueryExecutor(
            AccessScopePredicateFactory predicateFactory,
            AccessScopeResolver scopeResolver) {
        this.predicateFactory = predicateFactory;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Fetches a single entity by ID with scope enforcement.
     *
     * <p>Both out-of-scope and nonexistent IDs throw {@link ScopedAccessDeniedException}
     * (non-disclosure). Callers must not convert this to a 404.
     *
     * @param entityType the entity class
     * @param id         the requested ID
     * @param repository the scoped repository
     * @param <T>        entity type
     * @param <ID>       ID type
     * @return the entity if it exists and is in scope
     * @throws ScopedAccessDeniedException if the entity is not in scope or does not exist
     */
    public <T extends ScopedEntity, ID> T findById(
            Class<T> entityType,
            ID id,
            ScopedRepository<T, ID> repository) {

        AccessScope scope = scopeResolver.resolve();
        Specification<T> scopeSpec = predicateFactory.scopeFor(entityType, scope);
        Specification<T> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<T> composed = scopeSpec.and(idSpec);

        Optional<T> result = repository.findOne(composed);

        if (result.isEmpty()) {
            // Non-disclosure: log with hashed id, never echo requested id to client
            String idHash = id instanceof UUID uid
                    ? new ScopedAccessDeniedException(entityType.getSimpleName(), uid)
                            .getResourceIdHash().orElse("[unknown]")
                    : "[non-uuid-id]";

            log.warn("Scoped access denied: actor={}, roles={}, resourceType={}, resourceIdHash={}, traceId={}",
                    scope.userId(),
                    scope.roles(),
                    entityType.getSimpleName(),
                    idHash,
                    currentTraceId());

            throw new ScopedAccessDeniedException(entityType.getSimpleName(),
                    id instanceof UUID uid ? uid : UUID.nameUUIDFromBytes(id.toString().getBytes()));
        }

        return result.get();
    }

    /**
     * Fetches a paginated list of entities with scope enforcement applied to both the data
     * query and the {@code COUNT} query so {@code totalElements} is always scoped.
     *
     * @param entityType     the entity class
     * @param additionalSpec optional caller-supplied filter (ANDed with the scope predicate)
     * @param pageable       pagination parameters
     * @param repository     the scoped repository
     * @param <T>            entity type
     * @return a scoped {@link Page}
     */
    public <T extends ScopedEntity> Page<T> findAll(
            Class<T> entityType,
            @Nullable Specification<T> additionalSpec,
            Pageable pageable,
            ScopedRepository<T, ?> repository) {

        AccessScope scope = scopeResolver.resolve();
        Specification<T> scopeSpec = predicateFactory.scopeFor(entityType, scope);
        Specification<T> composed = additionalSpec != null
                ? scopeSpec.and(additionalSpec)
                : scopeSpec;

        return repository.findAll(composed, pageable);
    }

    /**
     * Fetches an unpaginated list of entities with scope enforcement.
     *
     * @param entityType     the entity class
     * @param additionalSpec optional caller-supplied filter (ANDed with the scope predicate)
     * @param repository     the scoped repository
     * @param <T>            entity type
     * @return a scoped {@link List}
     */
    public <T extends ScopedEntity> List<T> findAll(
            Class<T> entityType,
            @Nullable Specification<T> additionalSpec,
            ScopedRepository<T, ?> repository) {

        AccessScope scope = scopeResolver.resolve();
        Specification<T> scopeSpec = predicateFactory.scopeFor(entityType, scope);
        Specification<T> composed = additionalSpec != null
                ? scopeSpec.and(additionalSpec)
                : scopeSpec;

        return repository.findAll(composed);
    }

    /**
     * Returns the current trace ID from MDC if available, for structured log correlation.
     */
    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null ? traceId : "none";
    }
}
