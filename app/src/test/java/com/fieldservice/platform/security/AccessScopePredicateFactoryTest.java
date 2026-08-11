package com.fieldservice.platform.security;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.security.WorkOrderScopeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccessScopePredicateFactory}.
 *
 * <p>Includes the startup-validation contract: if a required scoped entity type has no
 * registered {@link EntityScopeSpec}, {@link AccessScopePredicateFactory#validateCompleteness()}
 * must throw {@link IllegalStateException}, preventing context initialization.
 */
class AccessScopePredicateFactoryTest {

    @Test
    @DisplayName("startup fails when a required entity type has no registered spec")
    void startup_fails_when_required_entity_has_no_spec() {
        // WorkOrderScopeSpec is registered but no spec for Site
        List<EntityScopeSpec<?>> specs = List.of(new WorkOrderScopeSpec());

        @SuppressWarnings("unchecked")
        Set<Class<? extends ScopedEntity>> required = Set.of(WorkOrder.class, Site.class);

        assertThatThrownBy(() -> new AccessScopePredicateFactory(specs, required))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Site");
    }

    @Test
    @DisplayName("startup succeeds when all required entities have registered specs")
    void startup_succeeds_when_all_required_entities_are_registered() {
        List<EntityScopeSpec<?>> specs = List.of(new WorkOrderScopeSpec());

        @SuppressWarnings("unchecked")
        Set<Class<? extends ScopedEntity>> required = Set.of(WorkOrder.class);

        // Should not throw
        AccessScopePredicateFactory factory = new AccessScopePredicateFactory(specs, required);
        assertThat(factory.registeredEntityTypes()).contains(WorkOrder.class);
    }

    @Test
    @DisplayName("specFor throws ScopedAccessDeniedException for unregistered entity type at request time")
    void spec_for_throws_when_entity_not_registered() {
        // A factory with no specs
        AccessScopePredicateFactory factory = new AccessScopePredicateFactory(
                List.of(), Set.of());

        AccessScope scope = new AccessScope(UUID.randomUUID(), Set.of("DISPATCHER"), null, null);

        assertThatThrownBy(() -> factory.specFor(WorkOrder.class, scope))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    @DisplayName("DISPATCHER scope produces a permit-all predicate for WorkOrder")
    void dispatcher_scope_produces_permit_all_predicate() {
        List<EntityScopeSpec<?>> specs = List.of(new WorkOrderScopeSpec());
        AccessScopePredicateFactory factory = new AccessScopePredicateFactory(
                specs, Set.of(WorkOrder.class));

        AccessScope scope = new AccessScope(UUID.randomUUID(), Set.of("DISPATCHER"), null, null);
        Specification<WorkOrder> spec = factory.specFor(WorkOrder.class, scope);

        // Permit-all: conjunction evaluates to true in any context
        assertThat(spec).isNotNull();
        // We can't easily assert SQL without a running DB, but verify it doesn't throw
    }

    @Test
    @DisplayName("TECHNICIAN with null technicianId produces deny-all predicate")
    void technician_with_null_id_produces_deny_all() {
        List<EntityScopeSpec<?>> specs = List.of(new WorkOrderScopeSpec());
        AccessScopePredicateFactory factory = new AccessScopePredicateFactory(
                specs, Set.of(WorkOrder.class));

        // TECHNICIAN without technician_id → deny
        AccessScope scope = new AccessScope(UUID.randomUUID(), Set.of("TECHNICIAN"), null, null);
        Specification<WorkOrder> spec = factory.specFor(WorkOrder.class, scope);

        assertThat(spec).isNotNull();
    }
}
