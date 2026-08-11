package com.fieldservice.security;

import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.inventory.StockMovement;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.WorkOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@link AccessScopePredicateFactory} registers providers for all
 * scoped entity types and that the startup validation passes.
 *
 * <p>If a scoped entity type is missing a provider, {@link AccessScopePredicateFactory#validateCompleteness()}
 * throws an {@link IllegalStateException} at startup, which would cause the Spring context
 * to fail to load — this test would therefore fail with a context load error.
 */
class AccessScopePredicateFactoryStartupTest extends AbstractIntegrationTest {

    @Autowired
    private AccessScopePredicateFactory predicateFactory;

    @Test
    void allScopedEntityTypesHaveRegisteredProviders() {
        // All five scoped entity types must have providers
        assertThat(predicateFactory.hasProviderFor(WorkOrder.class))
                .as("WorkOrder must have a predicate provider")
                .isTrue();

        assertThat(predicateFactory.hasProviderFor(Site.class))
                .as("Site must have a predicate provider")
                .isTrue();

        assertThat(predicateFactory.hasProviderFor(Asset.class))
                .as("Asset must have a predicate provider")
                .isTrue();

        assertThat(predicateFactory.hasProviderFor(Assignment.class))
                .as("Assignment must have a predicate provider")
                .isTrue();

        assertThat(predicateFactory.hasProviderFor(StockMovement.class))
                .as("StockMovement must have a predicate provider")
                .isTrue();
    }

    @Test
    void registeredEntityTypesCoversAllScopedEntities() {
        assertThat(predicateFactory.registeredEntityTypes())
                .contains(WorkOrder.class, Site.class, Asset.class, Assignment.class, StockMovement.class);
    }
}
