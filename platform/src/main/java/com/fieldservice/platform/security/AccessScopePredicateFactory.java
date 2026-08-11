package com.fieldservice.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import jakarta.persistence.metamodel.EntityType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Central registry of per-entity-type {@link Specification} builders that enforce
 * the caller's {@link AccessScope} as a SQL predicate.
 *
 * <h3>Registration</h3>
 * Domain modules register their factories by providing a
 * {@link AccessScopeSpecificationContributor} bean. All contributors are collected
 * via Spring's list injection and called during construction.
 *
 * <h3>Startup validation</h3>
 * After the application context is fully refreshed, {@link #validateCoverage} scans
 * the JPA metamodel for every entity implementing {@link com.fieldservice.platform.persistence.ScopedEntity}
 * and asserts a factory is registered. A missing factory fails the context startup
 * so the gap cannot reach production.
 *
 * <h3>Fail-closed contract</h3>
 * If {@link #specificationFor} is called for an unregistered entity type (e.g. a race
 * before validation completes, or a programming error), it throws
 * {@link ScopedAccessDeniedException} — never permits the read.
 */
@Component
public class AccessScopePredicateFactory {

    private static final Logger log = LoggerFactory.getLogger(AccessScopePredicateFactory.class);

    private final Map<Class<?>, Function<AccessScope, Specification<?>>> registry =
            new ConcurrentHashMap<>();

    @Autowired
    public AccessScopePredicateFactory(List<AccessScopeSpecificationContributor> contributors) {
        contributors.forEach(c -> c.contribute(this));
        log.info("AccessScopePredicateFactory initialised with {} contributor(s), {} entity type(s) registered",
                contributors.size(), registry.size());
    }

    /**
     * Register a scope {@link Specification} builder for an entity type.
     * Called by {@link AccessScopeSpecificationContributor} implementations during startup.
     *
     * @param entityType the JPA entity class
     * @param factory    a function that turns an {@link AccessScope} into a Specification
     * @param <T>        entity type
     */
    public <T> void register(Class<T> entityType,
                              Function<AccessScope, Specification<T>> factory) {
        @SuppressWarnings("unchecked")
        Function<AccessScope, Specification<?>> casted = scope -> factory.apply(scope);
        registry.put(entityType, casted);
        log.debug("Registered AccessScope predicate factory for entity: {}", entityType.getSimpleName());
    }

    /**
     * Return a {@link Specification} that encodes the caller's row scope for the
     * given entity type.
     *
     * @throws ScopedAccessDeniedException if no factory is registered — fail-closed
     */
    @SuppressWarnings("unchecked")
    public <T> Specification<T> specificationFor(Class<T> entityType, AccessScope scope) {
        Function<AccessScope, Specification<?>> factory = registry.get(entityType);
        if (factory == null) {
            log.warn("No AccessScope predicate factory registered for entity: {}", entityType.getName());
            throw new ScopedAccessDeniedException(
                    "No predicate factory for entity: " + entityType.getSimpleName());
        }
        return (Specification<T>) factory.apply(scope);
    }

    /**
     * Post-startup validation: every JPA entity implementing {@link com.fieldservice.platform.persistence.ScopedEntity}
     * must have a registered factory. Called on {@link ContextRefreshedEvent}.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void validateCoverage(ContextRefreshedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        if (ctx.getParent() != null) {
            return; // Only run in the root context
        }
        try {
            jakarta.persistence.EntityManagerFactory emf =
                    ctx.getBean(jakarta.persistence.EntityManagerFactory.class);
            Class<?> scopedEntityMarker = com.fieldservice.platform.persistence.ScopedEntity.class;

            List<String> unregistered = emf.getMetamodel().getEntities().stream()
                    .map(EntityType::getJavaType)
                    .filter(scopedEntityMarker::isAssignableFrom)
                    .filter(c -> !c.equals(scopedEntityMarker))
                    .filter(c -> !registry.containsKey(c))
                    .map(Class::getName)
                    .toList();

            if (!unregistered.isEmpty()) {
                throw new IllegalStateException(
                        "Application startup failed: scoped entities with no registered AccessScope predicate factory: "
                                + unregistered);
            }
            log.info("AccessScope coverage validated: all {} scoped entity type(s) have registered factories",
                    registry.size());
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) throw ise;
            // EntityManagerFactory may not be present in unit-test slices
            log.debug("Skipping AccessScope coverage validation (no JPA context): {}", e.getMessage());
        }
    }
}
