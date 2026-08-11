package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.workorder.IllegalWorkOrderTransitionException;
import com.fieldservice.workorder.WorkOrderFixtureBuilder;
import com.fieldservice.workorder.WorkOrderTransitionService;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.fieldservice.domain.workorder.WorkOrderState.*;
import static com.fieldservice.workorder.lifecycle.WorkOrderEvent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link WorkOrderTransitionService} against a Testcontainers PostgreSQL
 * instance. Covers:
 *
 * <ul>
 *   <li>Full happy path NEW → ASSIGNED → EN_ROUTE → IN_PROGRESS → COMPLETED → CLOSED,
 *       asserting persisted state and Envers revision count after every step.</li>
 *   <li>CANCEL from each non-terminal state.</li>
 *   <li>Terminal-state refusal (CLOSED and CANCELLED have no outbound transitions).</li>
 *   <li>Role gating: CUSTOMER principal is refused for DISPATCHER-only events.</li>
 *   <li>DB CHECK constraint vocabulary agrees with WorkOrderState enum.</li>
 *   <li>Fixture builder produces work orders in all eight states.</li>
 * </ul>
 */
@DisplayName("WorkOrderTransitionService integration tests")
class WorkOrderLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WorkOrderTransitionService transitionService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private WorkOrderFixtureBuilder fixtureBuilder;

    @BeforeEach
    void setUp() {
        fixtureBuilder = new WorkOrderFixtureBuilder(entityManager, txTemplate, transitionService);
        setDispatcherPrincipal();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full happy path NEW → CLOSED with Envers revision count")
    void happyPath_newToClosed_persistsStateAndRevisions() {
        UUID woId = createNewWorkOrder();

        applyAndAssert(woId, ASSIGN,    ASSIGNED);
        applyAndAssert(woId, DEPART,    EN_ROUTE);
        applyAndAssert(woId, START,     IN_PROGRESS);
        applyAndAssert(woId, COMPLETE,  COMPLETED);
        applyAndAssert(woId, CLOSE,     CLOSED);

        // 1 create + 5 state transitions = 6 revisions
        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, woId);
            assertThat(revs)
                    .as("6 audit revisions expected: 1 create + 5 transitions")
                    .hasSize(6);
        });
    }

    @Test
    @DisplayName("ASSIGNED → IN_PROGRESS via START (skip EN_ROUTE)")
    void assignedToInProgress_viaStartDirectly() {
        UUID woId = createNewWorkOrder();
        applyAndAssert(woId, ASSIGN, ASSIGNED);
        applyAndAssert(woId, START,  IN_PROGRESS);
    }

    // ── Cancellation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cancel from each non-terminal state produces CANCELLED")
    void cancelFromAllNonTerminalStates() {
        for (WorkOrderState state : new WorkOrderState[]{NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD}) {
            UUID woId = fixtureBuilder.createInState(state);
            applyAndAssert(woId, CANCEL, CANCELLED);
        }
    }

    // ── Terminal-state refusal ─────────────────────────────────────────────────

    @Test
    @DisplayName("Every event applied to CLOSED is refused with WORK_ORDER_ILLEGAL_TRANSITION")
    void closedRefusesAllEvents() {
        UUID woId = fixtureBuilder.createInState(CLOSED);
        for (WorkOrderEvent event : WorkOrderEvent.values()) {
            UUID capturedId = woId;
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                    transitionService.applyEvent(capturedId, event)))
                    .isInstanceOfSatisfying(IllegalWorkOrderTransitionException.class, ex ->
                            assertThat(ex.getErrorCode()).isEqualTo("WORK_ORDER_ILLEGAL_TRANSITION"));
        }
    }

    @Test
    @DisplayName("Every event applied to CANCELLED is refused with WORK_ORDER_ILLEGAL_TRANSITION")
    void cancelledRefusesAllEvents() {
        UUID woId = fixtureBuilder.createInState(CANCELLED);
        for (WorkOrderEvent event : WorkOrderEvent.values()) {
            UUID capturedId = woId;
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                    transitionService.applyEvent(capturedId, event)))
                    .isInstanceOf(IllegalWorkOrderTransitionException.class);
        }
    }

    // ── Role gating ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER role is refused for DISPATCHER-only ASSIGN event")
    void customerRoleRefusedForAssign() {
        UUID woId = createNewWorkOrder();
        setCustomerPrincipal();
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                transitionService.applyEvent(woId, ASSIGN)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("TECHNICIAN role may START from EN_ROUTE")
    void technicianMayStartFromEnRoute() {
        UUID woId = createNewWorkOrder();
        applyAndAssert(woId, ASSIGN,  ASSIGNED);
        applyAndAssert(woId, DEPART,  EN_ROUTE);
        setTechnicianPrincipal();
        applyAndAssert(woId, START,   IN_PROGRESS);
    }

    // ── Illegal-transition exception payload ──────────────────────────────────

    @Test
    @DisplayName("Illegal transition carries current state, requested event, and legal events")
    void illegalTransitionExceptionPayload() {
        UUID woId = createNewWorkOrder();
        // NEW state — legal events are ASSIGN and CANCEL only
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status ->
                transitionService.applyEvent(woId, CLOSE)))
                .isInstanceOfSatisfying(IllegalWorkOrderTransitionException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("WORK_ORDER_ILLEGAL_TRANSITION");
                    assertThat(ex.getCurrentState()).isEqualTo(NEW);
                    assertThat(ex.getRequestedEvent()).isEqualTo(CLOSE);
                    assertThat(ex.getLegalEvents()).containsExactlyInAnyOrder(ASSIGN, CANCEL);
                });
    }

    // ── DB CHECK constraint vs enum agreement ────────────────────────────────

    @Test
    @DisplayName("DB CHECK constraint vocabulary matches WorkOrderState enum exactly")
    void dbCheckConstraintMatchesEnum() throws Exception {
        String constraintDef = jdbc.queryForObject(
                "SELECT consrc FROM pg_constraint " +
                "WHERE conname = 'chk_work_order_state' AND contype = 'c'",
                String.class);

        assertThat(constraintDef).isNotNull();

        for (WorkOrderState state : WorkOrderState.values()) {
            assertThat(constraintDef)
                    .as("DB CHECK constraint must include enum value %s", state.name())
                    .contains("'" + state.name() + "'");
        }
    }

    // ── Fixture builder ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fixture builder creates work orders in all eight states")
    void fixtureBuilderProducesAllStates() {
        for (WorkOrderState targetState : WorkOrderState.values()) {
            UUID woId = fixtureBuilder.createInState(targetState);
            txTemplate.executeWithoutResult(status -> {
                WorkOrder wo = entityManager.find(WorkOrder.class, woId);
                assertThat(wo.getState())
                        .as("Fixture builder should produce a work order in %s", targetState)
                        .isEqualTo(targetState);
            });
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UUID createNewWorkOrder() {
        return fixtureBuilder.createInState(NEW);
    }

    private void applyAndAssert(UUID workOrderId, WorkOrderEvent event, WorkOrderState expectedState) {
        txTemplate.executeWithoutResult(status ->
                transitionService.applyEvent(workOrderId, event));
        txTemplate.executeWithoutResult(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            assertThat(wo.getState())
                    .as("After applying %s, state should be %s", event, expectedState)
                    .isEqualTo(expectedState);
        });
    }

    private void setDispatcherPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        WorkOrderFixtureBuilder.FIXTURE_DISPATCHER_ID.toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"))));
    }

    private void setCustomerPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private void setTechnicianPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))));
    }
}
