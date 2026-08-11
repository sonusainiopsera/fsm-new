package com.fieldservice.platform.security;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry of per-entity-type row-scope predicate factories.
 *
 * <p>All {@link EntityScopeSpec} beans are collected and indexed by
 * {@link EntityScopeSpec#entityType()}. A required set of scoped entity classes is
 * cross-checked at {@link #validateCompleteness() startup} — if any required entity lacks
 * a registered spec the context fails to start, ensuring gaps cannot reach production.
 *
 * <p>This class is not annotated {@code @Component} — it is created by
 * {@code AccessScopePredicateConfiguration#accessScopePredicateFactory()} in the app
 * module which also supplies the {@code requiredEntityTypes} set.
 *
 * <p>Usage:
 * <pre>{@code
 * Specification<WorkOrder> spec = predicateFactory.specFor(WorkOrder.class, scope);
 * }</pre>
 *
 * @see EntityScopeSpec
 */
public class AccessScopePredicateFactory {

    private static final Logger log = LoggerFactory.getLogger(AccessScopePredicateFactory.class);

    private final Map<Class<?>, EntityScopeSpec<?>> registry;
    private final Set<Class<?>> requiredEntityTypes;

    /**
     * Constructs the factory and immediately validates completeness.
     *
     * <p>{@code specs} comes from all {@link EntityScopeSpec} beans in the application
     * context; {@code requiredEntityTypes} is contributed by domain configuration classes
     * that register the full set of scoped entities in their module.
     *
     * @throws IllegalStateException if any required entity type has no registered spec
     */
    public AccessScopePredicateFactory(
            List<EntityScopeSpec<?>> specs,
            Set<Class<? extends ScopedEntity>> requiredEntityTypes) {
        this.registry = specs.stream()
                .collect(Collectors.toUnmodifiableMap(
                        EntityScopeSpec::entityType,
                        Function.identity()));
        this.requiredEntityTypes = Set.copyOf(requiredEntityTypes);
        validateCompleteness();
    }

    /**
     * Validates that every required scoped entity has a registered predicate.
     * Called from the constructor so that misconfiguration fails at object-creation
     * time rather than at first use.
     *
     * @throws IllegalStateException if any required entity type has no registered spec
     */
    @PostConstruct
    public void validateCompleteness() {
        List<Class<?>> missing = requiredEntityTypes.stream()
                .filter(type -> !registry.containsKey(type))
                .toList();
        if (!missing.isEmpty()) {
            String names = missing.stream()
                    .map(Class::getSimpleName)
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "Missing AccessScope predicate factory for scoped entity types: [" + names + "]. "
                    + "Add an EntityScopeSpec<T> bean for each type before context initialisation.");
        }
        log.info("access_scope_predicates_validated registered={} required={}",
                registry.size(), requiredEntityTypes.size());
    }

    /**
     * Returns the scope {@link Specification} for the given entity class and scope.
     *
     * @throws ScopedAccessDeniedException if no spec is registered for the entity type —
     *                                     this is a programming error; startup validation
     *                                     should have caught it
     */
    @SuppressWarnings("unchecked")
    public <T extends ScopedEntity> Specification<T> specFor(Class<T> entityClass, AccessScope scope) {
        EntityScopeSpec<T> spec = (EntityScopeSpec<T>) registry.get(entityClass);
        if (spec == null) {
            throw new ScopedAccessDeniedException(
                    "No scope predicate registered for entity type: " + entityClass.getSimpleName());
        }
        return spec.specFor(scope);
    }

    /** Returns an unmodifiable view of all registered entity types, for testing. */
    public Set<Class<?>> registeredEntityTypes() {
        return registry.keySet();
    }
}
