package com.fieldservice.platform.security;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central factory for per-entity row-scope JPA {@link Specification} predicates.
 *
 * <p>At startup, this factory:
 * <ol>
 *   <li>Collects all {@link ScopedEntityPredicateProvider} beans registered in the context.</li>
 *   <li>Introspects the JPA metamodel to find all {@link ScopedEntity} entity types.</li>
 *   <li><strong>Fails startup</strong> if any scoped entity type has no registered predicate
 *       provider — ensuring gaps cannot reach production.</li>
 * </ol>
 *
 * <p>At request time, callers invoke {@link #scopeFor(Class, AccessScope)} which looks up the
 * registered provider and delegates to {@link ScopedEntityPredicateProvider#forScope(AccessScope)}.
 * A missing provider throws {@link IllegalStateException} at request time as a second line of
 * defence, but should be impossible if startup validation passed.
 */
@Component
public class AccessScopePredicateFactory {

    private static final Logger log = LoggerFactory.getLogger(AccessScopePredicateFactory.class);

    private final Map<Class<? extends ScopedEntity>, ScopedEntityPredicateProvider<?>> providers;
    private final EntityManagerFactory entityManagerFactory;

    @SuppressWarnings("unchecked")
    public AccessScopePredicateFactory(
            List<ScopedEntityPredicateProvider<?>> providers,
            EntityManagerFactory entityManagerFactory) {

        this.entityManagerFactory = entityManagerFactory;
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        p -> (Class<? extends ScopedEntity>) p.getEntityType(),
                        Function.identity()
                ));
    }

    /**
     * Validates that every scoped entity registered in the JPA metamodel has a corresponding
     * predicate provider. Fails fast at startup if any provider is missing.
     *
     * <p>This method is called after the application context is fully started so that all
     * {@link ScopedEntityPredicateProvider} beans are guaranteed to be registered.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void validateCompleteness() {
        Set<Class<?>> scopedEntityTypes = entityManagerFactory.getMetamodel()
                .getEntities()
                .stream()
                .map(EntityType::getJavaType)
                .filter(ScopedEntity.class::isAssignableFrom)
                .filter(c -> !c.isInterface())
                .collect(Collectors.toSet());

        List<String> missingProviders = scopedEntityTypes.stream()
                .filter(type -> !providers.containsKey(type))
                .map(Class::getName)
                .sorted()
                .toList();

        if (!missingProviders.isEmpty()) {
            throw new IllegalStateException(
                    "Missing AccessScope predicate providers for scoped entity types: " +
                    missingProviders +
                    ". Each entity implementing ScopedEntity must have a registered " +
                    "ScopedEntityPredicateProvider bean.");
        }

        log.info("AccessScopePredicateFactory validated. Scoped entities covered: {}",
                scopedEntityTypes.stream()
                        .map(Class::getSimpleName)
                        .sorted()
                        .toList());
    }

    /**
     * Returns a JPA {@link Specification} predicate for the given entity type and access scope.
     *
     * @param entityType the scoped entity class
     * @param scope      the resolved access scope for the current request
     * @param <T>        entity type
     * @return the scope predicate; never {@code null}
     * @throws IllegalStateException if no provider is registered for {@code entityType}
     */
    @SuppressWarnings("unchecked")
    public <T extends ScopedEntity> Specification<T> scopeFor(Class<T> entityType, AccessScope scope) {
        ScopedEntityPredicateProvider<T> provider =
                (ScopedEntityPredicateProvider<T>) providers.get(entityType);

        if (provider == null) {
            throw new IllegalStateException(
                    "No AccessScope predicate provider registered for entity type: " +
                    entityType.getName() +
                    ". This should have been caught at startup — check provider registration.");
        }

        return provider.forScope(scope);
    }

    /**
     * Returns {@code true} if a predicate provider has been registered for the given entity type.
     * Intended for use in tests.
     */
    public boolean hasProviderFor(Class<?> entityType) {
        return providers.containsKey(entityType);
    }

    /**
     * Returns the set of entity types for which providers have been registered.
     * Intended for use in tests.
     */
    public Set<Class<? extends ScopedEntity>> registeredEntityTypes() {
        return providers.keySet();
    }
}
