package com.fieldservice.app.config;

import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.EntityScopeSpec;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Wires the {@link AccessScopePredicateFactory} with the complete set of scoped entity
 * types known to this application.
 *
 * <p>The factory is configured with a {@code requiredEntityTypes} set. At startup, the
 * factory's {@code @PostConstruct} method validates that every entity in this set has a
 * registered {@link EntityScopeSpec} bean — the application context fails to initialise
 * if any is missing, satisfying the "must fail at startup, not at request time" constraint.
 *
 * <h3>Adding a new scoped entity</h3>
 * <ol>
 *   <li>Implement {@link com.fieldservice.platform.persistence.ScopedEntity} on the entity.</li>
 *   <li>Add an {@link EntityScopeSpec} {@code @Component} for the new entity type.</li>
 *   <li>Add the entity class to the {@code requiredEntityTypes} set below.</li>
 * </ol>
 * If step 3 is forgotten, context startup will fail with a descriptive error.
 */
@Configuration
public class AccessScopePredicateConfiguration {

    /**
     * The set of entity classes that <em>must</em> have a registered
     * {@link EntityScopeSpec}. Add every new scoped entity here.
     */
    @SuppressWarnings("unchecked")
    private static final Set<Class<? extends ScopedEntity>> REQUIRED_ENTITY_TYPES = Set.of(
            WorkOrder.class,
            Site.class,
            CustomerAccount.class
    );

    @Bean
    public AccessScopePredicateFactory accessScopePredicateFactory(
            List<EntityScopeSpec<?>> specs) {
        return new AccessScopePredicateFactory(specs, REQUIRED_ENTITY_TYPES);
    }
}
