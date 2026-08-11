package com.fieldservice.security;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopePredicateFactory;
import com.fieldservice.platform.security.Role;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.fieldservice.security.TestJwtFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-role probe matrix for WorkOrder scope enforcement.
 *
 * <p>Tests the core scope predicate logic by directly composing predicates from
 * {@link AccessScopePredicateFactory} and executing them via {@link WorkOrderRepository}.
 * This verifies that:
 * <ul>
 *   <li>The correct rows are returned for each principal type</li>
 *   <li>{@code totalElements} (COUNT query) uses the same scope predicate as the data query</li>
 *   <li>Out-of-scope and nonexistent IDs are treated identically (non-disclosure)</li>
 *   <li>Scope changes (reassignment) take effect immediately on the next query</li>
 * </ul>
 *
 * <p>Fixture topology (from V100__test_fixtures.sql):
 * <pre>
 *   ACCT_A: site_a1 (wo_a1 → TECH_1, wo_unassigned → null)
 *           site_a2 (wo_a2 → TECH_2)
 *   ACCT_B: site_b1 (wo_b1 → TECH_1)
 * </pre>
 */
@Transactional
class CrossRoleProbeMatrixTest extends AbstractIntegrationTest {

    @Autowired
    private AccessScopePredicateFactory predicateFactory;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    // Helper: execute scoped read for a given AccessScope

    private Page<WorkOrder> listWorkOrders(AccessScope scope) {
        Specification<WorkOrder> spec = predicateFactory.scopeFor(WorkOrder.class, scope);
        return workOrderRepository.findAll(spec, PageRequest.of(0, 50));
    }

    private WorkOrder fetchWorkOrder(UUID id, AccessScope scope) {
        Specification<WorkOrder> scopeSpec = predicateFactory.scopeFor(WorkOrder.class, scope);
        Specification<WorkOrder> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Optional<WorkOrder> result = workOrderRepository.findOne(scopeSpec.and(idSpec));
        return result.orElseThrow(() -> new ScopedAccessDeniedException("WorkOrder", id));
    }

    // =========================================================================
    // DISPATCHER — permit-all
    // =========================================================================

    @Nested
    @DisplayName("DISPATCHER — permit-all, sees all 4 work orders")
    class DispatcherProbe {

        private final AccessScope scope = new AccessScope(
                DISPATCHER_USER_ID, Set.of(Role.DISPATCHER), null, Set.of());

        @Test
        void listAll_returnsAllFourWorkOrders() {
            Page<WorkOrder> page = listWorkOrders(scope);
            assertThat(page.getTotalElements())
                    .as("Dispatcher must see all work orders")
                    .isEqualTo(4);
            assertThat(page.getContent()).hasSize(4);
        }

        @Test
        void fetchWoA1_succeeds() {
            WorkOrder wo = fetchWorkOrder(WO_A1, scope);
            assertThat(wo.getId()).isEqualTo(WO_A1);
        }

        @Test
        void fetchWoB1_succeeds() {
            WorkOrder wo = fetchWorkOrder(WO_B1, scope);
            assertThat(wo.getId()).isEqualTo(WO_B1);
        }
    }

    // =========================================================================
    // MANAGER — permit-all
    // =========================================================================

    @Nested
    @DisplayName("MANAGER — permit-all")
    class ManagerProbe {

        private final AccessScope scope = new AccessScope(
                MANAGER_USER_ID, Set.of(Role.MANAGER), null, Set.of());

        @Test
        void listAll_returnsAllFourWorkOrders() {
            Page<WorkOrder> page = listWorkOrders(scope);
            assertThat(page.getTotalElements()).isEqualTo(4);
        }
    }

    // =========================================================================
    // TECHNICIAN 1 — assigned to wo_a1 and wo_b1 (non-overlapping sites)
    // =========================================================================

    @Nested
    @DisplayName("TECHNICIAN 1 — assigned to wo_a1 and wo_b1")
    class Tech1Probe {

        private final AccessScope scope = new AccessScope(
                TECH_1_USER_ID, Set.of(Role.TECHNICIAN), TECH_1_ID, Set.of());

        @Test
        void listWorkOrders_returnsOnlyAssignedWorkOrders() {
            Page<WorkOrder> page = listWorkOrders(scope);

            assertThat(page.getTotalElements())
                    .as("totalElements must be scoped — not the global count of 4")
                    .isEqualTo(2);
            assertThat(page.getContent())
                    .extracting(WorkOrder::getId)
                    .containsExactlyInAnyOrder(WO_A1, WO_B1);
        }

        @Test
        void fetchWoA1_succeeds() {
            WorkOrder wo = fetchWorkOrder(WO_A1, scope);
            assertThat(wo.getId()).isEqualTo(WO_A1);
        }

        @Test
        void fetchWoA2_denied_assignedToTech2() {
            // wo_a2 is assigned to Tech 2, not Tech 1 — must be denied (non-disclosure 403)
            assertThatThrownBy(() -> fetchWorkOrder(WO_A2, scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        void fetchNonExistentId_returnsSameExceptionAsForbiddenId() {
            // Non-disclosure: both out-of-scope and nonexistent IDs throw the same exception type
            assertThatThrownBy(() -> fetchWorkOrder(UUID.randomUUID(), scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        void fetchUnassignedWorkOrder_denied() {
            // wo_unassigned has no assignedTechnicianId — tech 1 must not see it
            assertThatThrownBy(() -> fetchWorkOrder(WO_UNASSIGNED, scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    // =========================================================================
    // TECHNICIAN 2 — assigned only to wo_a2
    // =========================================================================

    @Nested
    @DisplayName("TECHNICIAN 2 — assigned to wo_a2 only")
    class Tech2Probe {

        private final AccessScope scope = new AccessScope(
                TECH_2_USER_ID, Set.of(Role.TECHNICIAN), TECH_2_ID, Set.of());

        @Test
        void listWorkOrders_returnsOnlyOwnAssignment() {
            Page<WorkOrder> page = listWorkOrders(scope);

            assertThat(page.getTotalElements())
                    .as("totalElements must be scoped")
                    .isEqualTo(1);
            assertThat(page.getContent())
                    .extracting(WorkOrder::getId)
                    .containsExactly(WO_A2);
        }

        @Test
        void fetchWoA1_denied_assignedToTech1() {
            assertThatThrownBy(() -> fetchWorkOrder(WO_A1, scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    // =========================================================================
    // CUSTOMER (Account A + Account B) — sees all 4 work orders (union)
    // =========================================================================

    @Nested
    @DisplayName("CUSTOMER linked to Account A and B — sees all ACCT_A and ACCT_B work orders")
    class CustomerBothAccountsProbe {

        private final AccessScope scope = new AccessScope(
                CUSTOMER_USER_ID, Set.of(Role.CUSTOMER), null,
                Set.of(ACCT_A, ACCT_B));

        @Test
        void listWorkOrders_returnsAllAcrossLinkedAccounts() {
            Page<WorkOrder> page = listWorkOrders(scope);
            // All 4 work orders are on sites belonging to ACCT_A or ACCT_B
            assertThat(page.getTotalElements()).isEqualTo(4);
        }

        @Test
        void fetchWoA1_succeeds() {
            WorkOrder wo = fetchWorkOrder(WO_A1, scope);
            assertThat(wo.getId()).isEqualTo(WO_A1);
        }

        @Test
        void fetchWoB1_succeeds() {
            WorkOrder wo = fetchWorkOrder(WO_B1, scope);
            assertThat(wo.getId()).isEqualTo(WO_B1);
        }
    }

    // =========================================================================
    // CUSTOMER (Account A only) — sees wo_a1, wo_a2, wo_unassigned; not wo_b1
    // =========================================================================

    @Nested
    @DisplayName("CUSTOMER linked to Account A only — 3 work orders, not wo_b1")
    class CustomerAccountAOnlyProbe {

        private final AccessScope scope = new AccessScope(
                CUSTOMER_USER_ID, Set.of(Role.CUSTOMER), null, Set.of(ACCT_A));

        @Test
        void listWorkOrders_returnsOnlyAccountAWorkOrders() {
            Page<WorkOrder> page = listWorkOrders(scope);

            // 3 work orders on ACCT_A sites (wo_a1, wo_a2, wo_unassigned)
            assertThat(page.getTotalElements())
                    .as("totalElements must reflect scoped count of 3, not global 4")
                    .isEqualTo(3);
            assertThat(page.getContent())
                    .extracting(WorkOrder::getId)
                    .containsExactlyInAnyOrder(WO_A1, WO_A2, WO_UNASSIGNED)
                    .doesNotContain(WO_B1);
        }

        @Test
        void fetchWoB1_denied_belongsToAccountB() {
            assertThatThrownBy(() -> fetchWorkOrder(WO_B1, scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }

        @Test
        void probeNonExistentId_returnsSameExceptionAsForbiddenId() {
            // Non-disclosure: verify both paths produce the same exception type.
            // A cross-role probe cannot distinguish a forbidden resource from a nonexistent one.
            ScopedAccessDeniedException forForbidden = null;
            ScopedAccessDeniedException forNonExistent = null;

            try {
                fetchWorkOrder(WO_B1, scope);
            } catch (ScopedAccessDeniedException e) {
                forForbidden = e;
            }

            try {
                fetchWorkOrder(UUID.randomUUID(), scope);
            } catch (ScopedAccessDeniedException e) {
                forNonExistent = e;
            }

            assertThat(forForbidden).isNotNull().isInstanceOf(ScopedAccessDeniedException.class);
            assertThat(forNonExistent).isNotNull().isInstanceOf(ScopedAccessDeniedException.class);
            // Same exception class for both cases — existence cannot be inferred from the exception type
            assertThat(forForbidden.getClass()).isEqualTo(forNonExistent.getClass());
        }
    }

    // =========================================================================
    // CUSTOMER (Account B only)
    // =========================================================================

    @Nested
    @DisplayName("CUSTOMER linked to Account B only — 1 work order (wo_b1)")
    class CustomerAccountBOnlyProbe {

        private final AccessScope scope = new AccessScope(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000022"),
                Set.of(Role.CUSTOMER), null,
                Set.of(ACCT_B));

        @Test
        void listWorkOrders_returnsOnlyAccountBWorkOrders() {
            Page<WorkOrder> page = listWorkOrders(scope);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent())
                    .extracting(WorkOrder::getId)
                    .containsExactly(WO_B1);
        }

        @Test
        void fetchWoA1_denied_belongsToAccountA() {
            assertThatThrownBy(() -> fetchWorkOrder(WO_A1, scope))
                    .isInstanceOf(ScopedAccessDeniedException.class);
        }
    }

    // =========================================================================
    // CUSTOMER with no linked accounts — sees nothing
    // =========================================================================

    @Nested
    @DisplayName("CUSTOMER with no linked accounts — deny-all")
    class CustomerNoAccountsProbe {

        private final AccessScope scope = new AccessScope(
                UUID.randomUUID(), Set.of(Role.CUSTOMER), null, Set.of());

        @Test
        void listWorkOrders_returnsEmpty() {
            Page<WorkOrder> page = listWorkOrders(scope);

            assertThat(page.getTotalElements())
                    .as("Customer with no accounts must see zero work orders")
                    .isZero();
        }
    }

    // =========================================================================
    // Reassignment: scope changes take effect immediately (criterion 8)
    // =========================================================================

    @Nested
    @DisplayName("Reassignment — immediate scope revocation with no cache invalidation")
    class ReassignmentTest {

        @Test
        void technicianLosesAccessAfterReassignment_noCache() {
            AccessScope tech1Scope = new AccessScope(
                    TECH_1_USER_ID, Set.of(Role.TECHNICIAN), TECH_1_ID, Set.of());

            // Before reassignment: Tech 1 can see wo_a1
            WorkOrder wo = fetchWorkOrder(WO_A1, tech1Scope);
            assertThat(wo.getAssignedTechnicianId()).isEqualTo(TECH_1_ID);

            // Reassign to Tech 2 — updates the database record
            wo.setAssignedTechnicianId(TECH_2_ID);
            workOrderRepository.saveAndFlush(wo);

            // After reassignment: Tech 1 must immediately lose access.
            // The predicate evaluates the current DB state — no cache to invalidate.
            assertThatThrownBy(() -> fetchWorkOrder(WO_A1, tech1Scope))
                    .as("Tech 1 should lose access to wo_a1 immediately after reassignment")
                    .isInstanceOf(ScopedAccessDeniedException.class);

            // Tech 2 must now gain access
            AccessScope tech2Scope = new AccessScope(
                    TECH_2_USER_ID, Set.of(Role.TECHNICIAN), TECH_2_ID, Set.of());
            WorkOrder woForTech2 = fetchWorkOrder(WO_A1, tech2Scope);
            assertThat(woForTech2.getAssignedTechnicianId()).isEqualTo(TECH_2_ID);
        }

        @Test
        void scopedCountAlsoChangesAfterReassignment() {
            AccessScope tech1Scope = new AccessScope(
                    TECH_1_USER_ID, Set.of(Role.TECHNICIAN), TECH_1_ID, Set.of());

            // Before: Tech 1 sees 2 work orders
            assertThat(listWorkOrders(tech1Scope).getTotalElements()).isEqualTo(2);

            // Reassign wo_a1 away from tech 1
            WorkOrder woA1 = workOrderRepository.findById(WO_A1).orElseThrow();
            woA1.setAssignedTechnicianId(TECH_2_ID);
            workOrderRepository.saveAndFlush(woA1);

            // After: Tech 1 sees 1 work order (only wo_b1)
            Page<WorkOrder> afterPage = listWorkOrders(tech1Scope);
            assertThat(afterPage.getTotalElements())
                    .as("totalElements must reflect the updated scope")
                    .isEqualTo(1);
            assertThat(afterPage.getContent())
                    .extracting(WorkOrder::getId)
                    .containsExactly(WO_B1);
        }
    }
}
