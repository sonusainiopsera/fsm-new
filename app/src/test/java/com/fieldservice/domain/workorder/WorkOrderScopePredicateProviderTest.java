package com.fieldservice.domain.workorder;

import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.Role;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkOrderScopePredicateProvider}.
 *
 * <p>Tests verify predicate construction logic per role without a database.
 * Integration tests (CrossRoleProbeMatrixTest) verify generated SQL.
 */
class WorkOrderScopePredicateProviderTest {

    private static final UUID USER_ID  = UUID.randomUUID();
    private static final UUID TECH_ID  = UUID.randomUUID();
    private static final UUID ACCT_ID  = UUID.randomUUID();

    private WorkOrderScopePredicateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WorkOrderScopePredicateProvider();
    }

    @Test
    void dispatcherScope_returnsPermitAll() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.DISPATCHER), null, Set.of());
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(conjunction);
        verify(cb).conjunction();
    }

    @Test
    void adminScope_returnsPermitAll() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.ADMIN), null, Set.of());
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = spec.toPredicate(root, query, cb);
        assertThat(result).isSameAs(conjunction);
    }

    @Test
    void managerScope_returnsPermitAll() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.MANAGER), null, Set.of());
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Predicate result = spec.toPredicate(root, query, cb);
        assertThat(result).isSameAs(conjunction);
    }

    @Test
    void technicianScope_returnsAssignedTechnicianIdPredicate() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.TECHNICIAN), TECH_ID, Set.of());
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        var techField = mock(jakarta.persistence.criteria.Path.class);
        Predicate equalPredicate = mock(Predicate.class);

        when(root.get("assignedTechnicianId")).thenReturn(techField);
        when(cb.equal(techField, TECH_ID)).thenReturn(equalPredicate);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(equalPredicate);
        verify(root).get("assignedTechnicianId");
        verify(cb).equal(techField, TECH_ID);
    }

    @Test
    void technicianScope_throwsWhenTechnicianIdMissing() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.TECHNICIAN), null, Set.of());

        assertThatThrownBy(() -> provider.forScope(scope))
                .isInstanceOf(ScopedAccessDeniedException.class)
                .hasMessageContaining("technicianId");
    }

    @Test
    void customerScope_withAccounts_returnsInPredicate() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.CUSTOMER), null, Set.of(ACCT_ID));
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        Join<WorkOrder, ?> siteJoin = mock(Join.class);
        var customerAccountIdPath = mock(jakarta.persistence.criteria.Path.class);
        Predicate inPredicate = mock(Predicate.class);

        when(root.join("site", JoinType.INNER)).thenReturn(siteJoin);
        when(siteJoin.get("customerAccountId")).thenReturn(customerAccountIdPath);
        when(customerAccountIdPath.in(scope.customerAccountIds())).thenReturn(inPredicate);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(inPredicate);
        verify(root).join("site", JoinType.INNER);
    }

    @Test
    void customerScope_withNoAccounts_returnsDenyAll() {
        AccessScope scope = new AccessScope(USER_ID, Set.of(Role.CUSTOMER), null, Set.of());
        Specification<WorkOrder> spec = provider.forScope(scope);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<WorkOrder> root = mock(Root.class);
        Predicate disjunction = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(disjunction);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(disjunction);
        verify(cb).disjunction();
    }

    @Test
    void unknownRole_throwsScopedAccessDeniedException() {
        AccessScope scope = new AccessScope(USER_ID, Set.of("ROLE_UNKNOWN"), null, Set.of());

        assertThatThrownBy(() -> provider.forScope(scope))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    void entityTypeIsWorkOrder() {
        assertThat(provider.getEntityType()).isEqualTo(WorkOrder.class);
    }
}
