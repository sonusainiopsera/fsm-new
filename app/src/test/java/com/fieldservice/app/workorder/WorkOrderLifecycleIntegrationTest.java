package com.fieldservice.app.workorder;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.domain.workorder.lifecycle.IllegalWorkOrderTransitionException;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderErrorCodes;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.domain.workorder.lifecycle.WorkOrderTransitionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.fieldservice.domain.workorder.lifecycle.WorkOrderEvent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-9: Integration test driving the transition service through the full happy path
 * NEW → ASSIGNED → EN_ROUTE → IN_PROGRESS → COMPLETED → CLOSED
 * against Testcontainers PostgreSQL, asserting persisted state and Envers revision count.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("WO-123 work order lifecycle integration test")
class WorkOrderLifecycleIntegrationTest extends AbstractIntegrationTest {

    // Shared UUIDs (site from V4 seed data)
    private static final UUID SITE_ID =
            UUID.fromString("aaaaaaaa-0001-0001-0001-000000000001");

    private static UUID workOrderId;

    @Autowired
    private WorkOrderTransitionService transitionService;

    @Autowired
    private WorkOrderRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        if (workOrderId == null) {
            // Create the work order once; subsequent tests advance it
            workOrderId = createWorkOrder("Integration Lifecycle WO");
        }
    }

    // ── AC-9: happy path NEW → CLOSED ─────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Work order starts in NEW state")
    void work_order_starts_in_new_state() {
        assertPersistedState(workOrderId, WorkOrderState.NEW);
    }

    @Test
    @Order(2)
    @DisplayName("ASSIGN: NEW → ASSIGNED")
    void assign_transitions_new_to_assigned() {
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(workOrderId, ASSIGN));
        assertPersistedState(workOrderId, WorkOrderState.ASSIGNED);
        assertEnversRevisionCount(workOrderId, 2); // create + assign
    }

    @Test
    @Order(3)
    @DisplayName("DEPART: ASSIGNED → EN_ROUTE")
    void depart_transitions_assigned_to_en_route() {
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(workOrderId, DEPART));
        assertPersistedState(workOrderId, WorkOrderState.EN_ROUTE);
        assertEnversRevisionCount(workOrderId, 3);
    }

    @Test
    @Order(4)
    @DisplayName("START: EN_ROUTE → IN_PROGRESS")
    void start_transitions_en_route_to_in_progress() {
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(workOrderId, START));
        assertPersistedState(workOrderId, WorkOrderState.IN_PROGRESS);
        assertEnversRevisionCount(workOrderId, 4);
    }

    @Test
    @Order(5)
    @DisplayName("COMPLETE: IN_PROGRESS → COMPLETED (guard LABOUR_TIME_RECORDED is permissive in test)")
    void complete_transitions_in_progress_to_completed() {
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(workOrderId, COMPLETE));
        assertPersistedState(workOrderId, WorkOrderState.COMPLETED);
        assertEnversRevisionCount(workOrderId, 5);
    }

    @Test
    @Order(6)
    @DisplayName("CLOSE: COMPLETED → CLOSED (terminal)")
    void close_transitions_completed_to_closed() {
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(workOrderId, CLOSE));
        assertPersistedState(workOrderId, WorkOrderState.CLOSED);
        assertEnversRevisionCount(workOrderId, 6);
    }

    // ── AC-7: terminal state refuses all events ────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("CANCEL on CLOSED terminal state throws IllegalWorkOrderTransitionException")
    void cancel_on_closed_throws_illegal_transition() {
        setAuth("disp-1", "DISPATCHER");
        assertThatThrownBy(() ->
                transact(() -> transitionService.transition(workOrderId, CANCEL)))
                .isInstanceOf(IllegalWorkOrderTransitionException.class)
                .extracting(t -> ((IllegalWorkOrderTransitionException) t).getCode())
                .isEqualTo(WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION);
    }

    @Test
    @Order(8)
    @DisplayName("ASSIGN on CLOSED terminal state throws IllegalWorkOrderTransitionException")
    void assign_on_closed_throws_illegal_transition() {
        setAuth("disp-1", "DISPATCHER");
        assertThatThrownBy(() ->
                transact(() -> transitionService.transition(workOrderId, ASSIGN)))
                .isInstanceOf(IllegalWorkOrderTransitionException.class);
    }

    // ── HOLD/RESUME branch ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("HOLD and RESUME cycle: IN_PROGRESS → ON_HOLD → IN_PROGRESS")
    void hold_resume_cycle() {
        UUID holdWo = createWorkOrder("Hold/Resume WO");

        // Advance to IN_PROGRESS
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(holdWo, ASSIGN));
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(holdWo, DEPART));
        transact(() -> transitionService.transition(holdWo, START));

        // Hold and resume
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(holdWo, HOLD));
        assertPersistedState(holdWo, WorkOrderState.ON_HOLD);

        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(holdWo, RESUME));
        assertPersistedState(holdWo, WorkOrderState.IN_PROGRESS);
    }

    // ── ADR-0007: cancellation from EN_ROUTE and ON_HOLD ─────────────────────

    @Test
    @Order(11)
    @DisplayName("ADR-0007: CANCEL from EN_ROUTE is permitted (DISPATCHER)")
    void cancel_from_en_route_is_permitted() {
        UUID woId = createWorkOrder("Cancel EN_ROUTE WO");
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(woId, ASSIGN));
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(woId, DEPART));
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(woId, CANCEL));
        assertPersistedState(woId, WorkOrderState.CANCELLED);
    }

    @Test
    @Order(12)
    @DisplayName("ADR-0007: CANCEL from ON_HOLD is permitted (DISPATCHER)")
    void cancel_from_on_hold_is_permitted() {
        UUID woId = createWorkOrder("Cancel ON_HOLD WO");
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(woId, ASSIGN));
        setAuth("tech-1", "TECHNICIAN");
        transact(() -> transitionService.transition(woId, DEPART));
        transact(() -> transitionService.transition(woId, START));
        transact(() -> transitionService.transition(woId, HOLD));
        setAuth("disp-1", "DISPATCHER");
        transact(() -> transitionService.transition(woId, CANCEL));
        assertPersistedState(woId, WorkOrderState.CANCELLED);
    }

    // ── DB CHECK constraint vocab matches enum ────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("DB CHECK constraint on work_order.state matches WorkOrderState enum values")
    void db_state_check_constraint_matches_enum() {
        // Attempt to insert an out-of-vocabulary state
        assertThatThrownBy(() ->
                jdbc.update(
                        "INSERT INTO work_order (id, title, state, site_id, version) " +
                        "VALUES (gen_random_uuid(), 'Bad State WO', 'PENDING', " +
                        "(SELECT id FROM site LIMIT 1), 0)"))
                .hasMessageContaining("chk_work_order_state");

        // Assert all WorkOrderState values ARE in the check constraint (none are out-of-vocab)
        for (WorkOrderState s : WorkOrderState.values()) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c " +
                    "JOIN pg_class r ON r.oid = c.conrelid " +
                    "WHERE r.relname = 'work_order' AND c.contype = 'c' " +
                    "AND c.consrc LIKE '%" + s.name() + "%'",
                    Integer.class))
                    .as("State %s must appear in work_order CHECK constraint", s.name())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private UUID createWorkOrder(String title) {
        AtomicReference<UUID> idRef = new AtomicReference<>();
        setAuth("disp-1", "DISPATCHER");
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            com.fieldservice.domain.site.Site site = em.getReference(
                    com.fieldservice.domain.site.Site.class, SITE_ID);
            WorkOrder wo = new WorkOrder(title, site, "MEDIUM");
            em.persist(wo);
            idRef.set(wo.getId());
        });
        return idRef.get();
    }

    private void assertPersistedState(UUID id, WorkOrderState expected) {
        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?", String.class, id);
        assertThat(state).isEqualTo(expected.name());
    }

    private void assertEnversRevisionCount(UUID id, int expected) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_aud WHERE id = ?", Integer.class, id);
        assertThat(count).as("Envers revision count for work order %s", id).isEqualTo(expected);
    }

    private void setAuth(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void transact(Runnable action) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> action.run());
    }
}
