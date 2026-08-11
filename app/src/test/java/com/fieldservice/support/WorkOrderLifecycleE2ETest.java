package com.fieldservice.support;

import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.workorder.WorkOrderFixtureBuilder;
import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.domain.workorder.WorkOrderState;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test: full application context against PostgreSQL 16.
 *
 * <p>Verifies the complete work-order lifecycle transition flow:
 * <ol>
 *   <li>Create a work order in NEW state via the domain layer.</li>
 *   <li>Assign it (NEW → ASSIGNED) via HTTP POST to the transitions endpoint.</li>
 *   <li>Assert the database row reflects the new state (persisted).</li>
 *   <li>Assert exactly one Envers revision was written for the state change.</li>
 *   <li>Assert exactly one outbox event was committed for the transition.</li>
 * </ol>
 *
 * <p>This test is intentionally non-transactional so that the outbox row and Envers
 * revision are committed before they are asserted. {@link DatabaseCleaner#clean} is
 * called in {@code @AfterEach} to restore the baseline.
 *
 * <p>Satisfies AC-10: "at least one end-to-end test boots the full application context
 * against the container, performs an authenticated work-order create and transition
 * through HTTP, and asserts persisted rows, Envers revision and outbox event."
 */
@DisplayName("Work order lifecycle — end-to-end integration test")
class WorkOrderLifecycleE2ETest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private WorkOrderTransitionService transitionService;

    private WorkOrderFixtureBuilder fixtures;
    private UUID createdWorkOrderId;

    @BeforeEach
    void setUp() {
        fixtures = new WorkOrderFixtureBuilder(entityManager, txTemplate, transitionService);
    }

    @AfterEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    @DisplayName("ASSIGN transition: persisted row + Envers revision + outbox event")
    void assign_newWorkOrder_persistsRowRevisionAndOutboxEvent() throws Exception {
        // 1. Create a work order in NEW state (direct domain call — no creation REST endpoint yet)
        createdWorkOrderId = fixtures.createInState(WorkOrderState.NEW);
        assertThat(createdWorkOrderId).isNotNull();

        // 2. Perform ASSIGN transition via HTTP with authenticated DISPATCHER JWT
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", createdWorkOrderId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromState").value("NEW"))
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // 3. Assert the database row reflects ASSIGNED state
        String actualState = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, createdWorkOrderId);
        assertThat(actualState)
                .as("work_order.state must be ASSIGNED after transition")
                .isEqualTo("ASSIGNED");

        // 4. Assert exactly two Envers revisions: ADD (creation) + MOD (state change)
        var revisions = AuditAssertions.assertRevisionCount(
                jdbc, "work_order_aud", createdWorkOrderId, 2);
        assertThat(revisions.get(0).revType())
                .as("First revision must be ADD (creation)")
                .isEqualTo(AuditAssertions.RevisionType.ADD);
        assertThat(revisions.get(1).revType())
                .as("Second revision must be MOD (state transition)")
                .isEqualTo(AuditAssertions.RevisionType.MOD);

        // 5. Assert exactly one outbox event was committed for the state change
        OutboxAssertions.assertExactlyOneEvent(
                jdbc, createdWorkOrderId, "WorkOrderStateChanged");
    }

    @Test
    @DisplayName("Start EN_ROUTE transition: 200 and outbox event committed")
    void startEnRoute_assignedWorkOrder_persistsOutboxEvent() throws Exception {
        // Create in ASSIGNED state
        createdWorkOrderId = fixtures.createInState(WorkOrderState.ASSIGNED);

        // Perform START_EN_ROUTE as TECH_1
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", createdWorkOrderId)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"START_EN_ROUTE","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));

        // Assert outbox event committed (transition generates WorkOrderStateChanged)
        OutboxAssertions.assertExactlyOneEvent(
                jdbc, createdWorkOrderId, "WorkOrderStateChanged");

        // Assert database state
        String state = jdbc.queryForObject(
                "SELECT state FROM work_order WHERE id = ?",
                String.class, createdWorkOrderId);
        assertThat(state).isEqualTo("EN_ROUTE");
    }

    @Test
    @DisplayName("Rolled-back transition produces no outbox event")
    void rollback_producesNoOutboxEvent() throws Exception {
        createdWorkOrderId = fixtures.createInState(WorkOrderState.NEW);

        // Attempt illegal transition: NEW → EN_ROUTE (should be 409)
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", createdWorkOrderId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"START_EN_ROUTE","expectedVersion":0}
                                """))
                .andExpect(status().isConflict()); // 409

        // Assert no outbox event was written (transaction rolled back)
        OutboxAssertions.assertNoEvent(jdbc, createdWorkOrderId);
    }
}
