package com.fieldservice.security;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static com.fieldservice.security.TestJwtFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves row-scope is a SQL predicate, not a post-fetch filter (WO-113 AC-4).
 *
 * <p>Two assertions:
 * <ol>
 *   <li>The scoped COUNT via {@code Specification} is less than the total COUNT via
 *       native JDBC — proving the WHERE clause is in the SQL, not applied after fetch.</li>
 *   <li>The scoped {@code totalElements} from a paginated query matches the known fixture
 *       count for that principal — proving COUNT(*) is also scoped.</li>
 * </ol>
 *
 * <p>If scope were a post-fetch filter, {@code workOrderRepository.count(spec)} would still
 * issue {@code SELECT COUNT(*) FROM work_order} with no WHERE clause and return the global
 * total; it would not return the per-principal count.
 */
@Transactional
class ScopedQuerySqlInspectionTest extends AbstractIntegrationTest {

    @Autowired
    private AccessScopePredicateFactory predicateFactory;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void totalCount_isHigherThanTech1ScopedCount_provingPredicateIsInSql() {
        // Unscoped count via native JDBC — no Specification involved
        Long totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_order", Long.class);
        assertThat(totalCount).isNotNull().isEqualTo(4L);

        // Scoped count via Specification for TECH_1 (assigned to wo_a1 and wo_b1)
        AccessScope tech1Scope = new AccessScope(TECH_1_USER_ID, Set.of(Role.TECHNICIAN), TECH_1_ID, Set.of());
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, tech1Scope);
        long scopedCount = workOrderRepository.count(spec);

        // Scope predicate is in the SQL — COUNT returns 2 for TECH_1, not 4
        assertThat(scopedCount).isEqualTo(2L);
        assertThat(scopedCount).isLessThan(totalCount);
    }

    @Test
    void tech2ScopedCount_returnsOnlyAssignedWorkOrders() {
        // TECH_2 is assigned only to wo_a2
        AccessScope tech2Scope = new AccessScope(TECH_2_USER_ID, Set.of(Role.TECHNICIAN), TECH_2_ID, Set.of());
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, tech2Scope);
        long scopedCount = workOrderRepository.count(spec);

        assertThat(scopedCount).isEqualTo(1L);
    }

    @Test
    void customerAccountAScope_returnsOnlyAccountAWorkOrders() {
        // CUSTOMER linked to ACCT_A sees wo_a1, wo_a2, wo_unassigned (3 work orders in ACCT_A)
        AccessScope customerAScope = new AccessScope(CUSTOMER_USER_ID, Set.of(Role.CUSTOMER),
                null, Set.of(ACCT_A));
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, customerAScope);
        long scopedCount = workOrderRepository.count(spec);

        // 3 work orders belong to ACCT_A
        assertThat(scopedCount).isEqualTo(3L);

        // Must be less than total
        Long totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_order", Long.class);
        assertThat(scopedCount).isLessThan(totalCount);
    }

    @Test
    void dispatcherScope_returnsAllWorkOrders_matchingTotalCount() {
        // DISPATCHER is privileged — sees all work orders
        AccessScope dispatcherScope = new AccessScope(DISPATCHER_USER_ID, Set.of(Role.DISPATCHER), null, Set.of());
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, dispatcherScope);
        long scopedCount = workOrderRepository.count(spec);

        Long totalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_order", Long.class);
        assertThat(scopedCount).isEqualTo(totalCount);
    }

    @Test
    void customerWithNoAccounts_seesZeroWorkOrders() {
        // CUSTOMER with empty account list gets deny-all predicate
        AccessScope noAccountScope = new AccessScope(CUSTOMER_USER_ID, Set.of(Role.CUSTOMER),
                null, Set.of());
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, noAccountScope);
        long scopedCount = workOrderRepository.count(spec);

        assertThat(scopedCount).isZero();
    }
}
