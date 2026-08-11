package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

import static com.fieldservice.app.security.TestJwtFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that scope changes (reassignment) take effect immediately on the next request
 * with no caching step required.
 *
 * <p>The test reassigns a work order away from Tech-One and confirms that:
 * <ol>
 *   <li>Tech-One can no longer fetch or list the reassigned work order.</li>
 *   <li>The new assignee (Tech-Two) gains visibility immediately.</li>
 *   <li>No cache invalidation step is needed — the scope predicate is evaluated
 *       fresh on every query.</li>
 * </ol>
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ScopeImmediateRevocationTest {

    @Autowired
    ScopedQueryExecutor scopedQueryExecutor;

    @Autowired
    WorkOrderRepository workOrderRepository;

    @Test
    @DisplayName("reassigning WO-001 from Tech-One to Tech-Two immediately revokes Tech-One visibility")
    @Transactional
    void reassignment_immediately_revokes_prior_assignee_visibility() {
        AccessScope techOneScope = new AccessScope(
                TECH_ONE_USER_ID, Set.of("TECHNICIAN"), TECH_ONE_ID, null);
        AccessScope techTwoScope = new AccessScope(
                TECH_TWO_USER_ID, Set.of("TECHNICIAN"), TECH_TWO_ID, null);

        // Before reassignment: Tech-One sees WO-001
        Optional<WorkOrder> beforeForTechOne = scopedQueryExecutor
                .findById(workOrderRepository, WO_001_ID, techOneScope, WorkOrder.class);
        assertThat(beforeForTechOne).isPresent();

        // Before reassignment: Tech-Two cannot see WO-001
        Optional<WorkOrder> beforeForTechTwo = scopedQueryExecutor
                .findById(workOrderRepository, WO_001_ID, techTwoScope, WorkOrder.class);
        assertThat(beforeForTechTwo).isEmpty();

        // Reassign WO-001 from Tech-One to Tech-Two
        WorkOrder wo = workOrderRepository.findById(WO_001_ID).orElseThrow();
        wo.assignTechnician(TECH_TWO_ID);
        workOrderRepository.save(wo);
        workOrderRepository.flush();

        // After reassignment: Tech-One can no longer see WO-001
        Optional<WorkOrder> afterForTechOne = scopedQueryExecutor
                .findById(workOrderRepository, WO_001_ID, techOneScope, WorkOrder.class);
        assertThat(afterForTechOne).isEmpty();

        // After reassignment: Tech-Two now sees WO-001 immediately
        Optional<WorkOrder> afterForTechTwo = scopedQueryExecutor
                .findById(workOrderRepository, WO_001_ID, techTwoScope, WorkOrder.class);
        assertThat(afterForTechTwo).isPresent();
    }

    @Test
    @DisplayName("scoped totalElements updates immediately after reassignment — no stale count")
    @Transactional
    void total_elements_reflects_reassignment_immediately() {
        AccessScope techOneScope = new AccessScope(
                TECH_ONE_USER_ID, Set.of("TECHNICIAN"), TECH_ONE_ID, null);

        // Tech-One initially has 2 work orders
        Page<WorkOrder> before = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 20), techOneScope, WorkOrder.class);
        assertThat(before.getTotalElements()).isEqualTo(2);

        // Unassign WO-001 from Tech-One
        WorkOrder wo = workOrderRepository.findById(WO_001_ID).orElseThrow();
        wo.unassign();
        workOrderRepository.save(wo);
        workOrderRepository.flush();

        // Tech-One now sees only 1 work order (WO-003)
        Page<WorkOrder> after = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 20), techOneScope, WorkOrder.class);
        assertThat(after.getTotalElements()).isEqualTo(1);
    }
}
