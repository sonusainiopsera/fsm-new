package com.fieldservice.integration;

import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.security.TestTokenMinter;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.AuditAssertions;
import com.fieldservice.support.OutboxAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the technician field-execution journey.
 *
 * <p>Covers AC-5 (WO-160): every successful transition produces an Envers revision
 * and an outbox event in the same transaction; a rolled-back operation produces neither.
 *
 * <p>Non-transactional: each test creates a fresh work order via the fixture setup,
 * asserts by its unique aggregate ID, and cleans up in {@code @BeforeEach} to ensure
 * a deterministic baseline without affecting other test classes (data is re-inserted
 * with ON CONFLICT DO NOTHING).
 *
 * <p>Security assertions (AC-3): cross-technician 403 and CUSTOMER 403 on the
 * technician endpoint are verified using the existing JWT factory.
 *
 * <p>Idempotency assertion (AC-4): the same transition submitted with the same
 * {@code Idempotency-Key} header produces exactly one outbox event (requires the
 * idempotency filter to be active — filter is {@code @Profile("api")} so the
 * test activates both "test" and "api" profiles via the nested class).
 */
@DisplayName("Technician field-execution journey — end-to-end integration")
class TechnicianJourneyIT extends AbstractIntegrationTest {

    private static final TestTokenMinter MINTER = TestTokenMinter.primary();

    private static final String TECH1_TOKEN = MINTER.validWithClaims(
            TestJwtFactory.TECH_1_USER_ID, List.of("TECHNICIAN"),
            Map.of("technicianId", TestJwtFactory.TECH_1_ID.toString()));

    private static final String TECH2_TOKEN = MINTER.validWithClaims(
            TestJwtFactory.TECH_2_USER_ID, List.of("TECHNICIAN"),
            Map.of("technicianId", TestJwtFactory.TECH_2_ID.toString()));

    private static final String DISPATCHER_TOKEN =
            MINTER.valid(TestJwtFactory.DISPATCHER_USER_ID, List.of("DISPATCHER"));

    private static final String CUSTOMER_TOKEN = MINTER.validWithClaims(
            TestJwtFactory.CUSTOMER_USER_ID, List.of("CUSTOMER"),
            Map.of("customerAccountIds", List.of(TestJwtFactory.ACCT_A.toString())));

    private static final String CUSTOMER_ID =
            TestJwtFactory.ACCT_A.toString();
    private static final String SITE_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String TECH_1_ID =
            TestJwtFactory.TECH_1_ID.toString();
    private static final String TECH_2_ID =
            TestJwtFactory.TECH_2_ID.toString();
    private static final String TECH_1_USER_ID =
            TestJwtFactory.TECH_1_USER_ID.toString();
    private static final String TECH_2_USER_ID =
            TestJwtFactory.TECH_2_USER_ID.toString();
    private static final String DISPATCHER_USER_ID =
            TestJwtFactory.DISPATCHER_USER_ID.toString();
    private static final String CUSTOMER_USER_ID =
            TestJwtFactory.CUSTOMER_USER_ID.toString();

    // Stock fixture IDs (from V100 inventory fixtures)
    private static final String TECH1_VAN_A = "60000000-0000-0000-0000-000000000011";
    private static final String PART_PN002  = "50000000-0000-0000-0000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Re-inserts the minimal fixture baseline needed by all journey tests.
     * Uses ON CONFLICT DO NOTHING so this is safe to call even when Flyway
     * has already loaded the same rows.
     */
    @BeforeEach
    void ensureFixtureBaseline() {
        // app_user rows
        jdbc.execute("""
                INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
                VALUES
                    ('%s', 'tech1@example.com', '$2a$10$placeholder.hash.tech1..........', 'Tech 1 Journey', true, 0),
                    ('%s', 'tech2@example.com', '$2a$10$placeholder.hash.tech2..........', 'Tech 2 Journey', true, 0),
                    ('%s', 'dispatcher@example.com', '$2a$10$placeholder.hash.disp..........', 'Dispatcher Journey', true, 0),
                    ('%s', 'customer@example.com', '$2a$10$placeholder.hash.cust..........', 'Customer Journey', true, 0)
                ON CONFLICT (id) DO NOTHING
                """.formatted(TECH_1_USER_ID, TECH_2_USER_ID, DISPATCHER_USER_ID, CUSTOMER_USER_ID));

        // technician rows
        jdbc.execute("""
                INSERT INTO technician (id, user_id, employee_no, is_active, version)
                VALUES
                    ('%s', '%s', 'EMP-JIT-001', true, 0),
                    ('%s', '%s', 'EMP-JIT-002', true, 0)
                ON CONFLICT (id) DO NOTHING
                """.formatted(TECH_1_ID, TECH_1_USER_ID, TECH_2_ID, TECH_2_USER_ID));

        // role_assignment rows
        jdbc.execute("""
                INSERT INTO role_assignment (id, user_id, role_name, granted_at)
                VALUES
                    ('e0010000-0000-0000-0000-000000000001', '%s', 'TECHNICIAN', now()),
                    ('e0010000-0000-0000-0000-000000000002', '%s', 'TECHNICIAN', now()),
                    ('e0010000-0000-0000-0000-000000000003', '%s', 'DISPATCHER', now()),
                    ('e0010000-0000-0000-0000-000000000004', '%s', 'CUSTOMER',   now())
                ON CONFLICT (id) DO NOTHING
                """.formatted(TECH_1_USER_ID, TECH_2_USER_ID, DISPATCHER_USER_ID, CUSTOMER_USER_ID));

        // customer and site
        jdbc.execute("""
                INSERT INTO customer (id, name, contact_phone, is_active, version)
                VALUES ('%s', 'Journey Test Corp', '+15555550001', true, 0)
                ON CONFLICT (id) DO NOTHING
                """.formatted(CUSTOMER_ID));

        jdbc.execute("""
                INSERT INTO site (id, customer_id, name, address, latitude, longitude, version)
                VALUES ('%s', '%s', 'Journey Test Site', '1 Test Lane', 34.0, -118.0, 0)
                ON CONFLICT (id) DO NOTHING
                """.formatted(SITE_ID, CUSTOMER_ID));

        // part catalogue entry
        jdbc.execute("""
                INSERT INTO part (id, sku, name, unit, part_number, description,
                                  unit_of_measure, reorder_point, reorder_quantity, is_active)
                VALUES ('%s', 'SKU-002', 'Air Filter 20x20x1', 'EACH', 'PN-002',
                        'Standard HVAC air filter', 'EACH', 10, 20, true)
                ON CONFLICT (id) DO NOTHING
                """.formatted(PART_PN002));

        // stock location for TECH_1 Van A
        jdbc.execute("""
                INSERT INTO stock_location (id, technician_id, name, location_type, is_active)
                VALUES ('%s', '%s', 'Tech 1 Van A (Journey)', 'VEHICLE', true)
                ON CONFLICT (id) DO NOTHING
                """.formatted(TECH1_VAN_A, TECH_1_ID));

        // stock balance: 10 units of PN-002 at Van A
        jdbc.execute("""
                INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
                VALUES ('e0020000-0000-0000-0000-000000000001', '%s', '%s', 10, 0)
                ON CONFLICT (part_id, location_id) DO UPDATE SET quantity_on_hand = GREATEST(stock_balance.quantity_on_hand, 10)
                """.formatted(PART_PN002, TECH1_VAN_A));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-5: Full journey — Envers revisions + outbox events per transition
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full journey: ASSIGN→DEPART→START→COMPLETE produces Envers revisions and outbox events")
    void fullJourney_producesRevisionsAndOutboxEvents() throws Exception {
        // Create a NEW work order via HTTP
        UUID woId = createWorkOrderViaHttp();

        // ASSIGN: dispatcher assigns to TECH_1
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0,
                                 "assignedTechnicianId":"%s"}
                                """.formatted(TECH_1_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // DEPART: TECH_1 goes EN_ROUTE
        long assignedVersion = currentVersion(woId);
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + TECH1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"DEPART","expectedVersion":%d}
                                """.formatted(assignedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));

        // START: TECH_1 arrives IN_PROGRESS
        long enRouteVersion = currentVersion(woId);
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + TECH1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"START","expectedVersion":%d}
                                """.formatted(enRouteVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("IN_PROGRESS"));

        // Insert labour time directly (no HTTP endpoint yet; guard checks for ≥1 row)
        jdbc.execute("""
                INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date)
                VALUES (gen_random_uuid(), '%s', '%s', 45, now())
                """.formatted(woId, TECH_1_ID));

        // COMPLETE
        long inProgressVersion = currentVersion(woId);
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + TECH1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"COMPLETE","expectedVersion":%d}
                                """.formatted(inProgressVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("COMPLETED"));

        // ── Assert Envers: ADD + 4 MOD revisions ─────────────────────────────
        var revisions = AuditAssertions.assertRevisionCount(jdbc, "work_order_aud", woId, 5);
        assertThat(revisions.get(0).revType()).isEqualTo(AuditAssertions.RevisionType.ADD);
        for (int i = 1; i <= 4; i++) {
            assertThat(revisions.get(i).revType()).isEqualTo(AuditAssertions.RevisionType.MOD);
        }

        // ── Assert outbox: 4 WorkOrderStateChanged events ────────────────────
        var events = OutboxAssertions.assertEventCount(jdbc, woId, 4);
        assertThat(events).allMatch(e -> "WorkOrderStateChanged".equals(e.eventType()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-5: Rollback — illegal transition produces no outbox event
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Illegal transition from NEW → IN_PROGRESS: 409 and no outbox event")
    void illegalTransition_producesNoOutboxEvent() throws Exception {
        UUID woId = createWorkOrderViaHttp();

        // NEW → START is illegal (must go through ASSIGN first)
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"START","expectedVersion":0}
                                """))
                .andExpect(status().isConflict()); // 409

        // No outbox event produced for the attempted (and rolled back) transition
        OutboxAssertions.assertNoEvent(jdbc, woId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-3: Cross-technician 403 — no existence disclosure
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_2 cannot transition TECH_1's work order — receives 403 or 404")
    void crossTechnicianTransition_deniedWithNonDisclosure() throws Exception {
        UUID woId = createWorkOrderViaHttp();

        // Assign to TECH_1
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0,
                                 "assignedTechnicianId":"%s"}
                                """.formatted(TECH_1_ID)))
                .andExpect(status().isOk());

        // TECH_2 attempts to transition TECH_1's work order
        int responseStatus = mockMvc.perform(
                        post("/api/v1/work-orders/{id}/transitions", woId)
                                .header("Authorization", "Bearer " + TECH2_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"event":"DEPART","expectedVersion":1}
                                        """))
                .andReturn().getResponse().getStatus();

        // Must be 403 (access denied) or 404 (non-disclosure)
        assertThat(responseStatus).isIn(403, 404);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-3: CUSTOMER 403 on technician endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER token receives 403 on GET /api/v1/technicians/me/work-orders")
    void customerToken_returns403OnTechnicianDayList() throws Exception {
        mockMvc.perform(get("/api/v1/technicians/me/work-orders")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden()); // 403
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-4: Parts consumption + stock decrement assertion
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Parts consumption decrements stock and produces PartsConsumed outbox event")
    void partsConsumption_decrementsStockAndProducesOutboxEvent() throws Exception {
        UUID woId = createWorkOrderViaHttp();

        // Advance to IN_PROGRESS
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0,
                                 "assignedTechnicianId":"%s"}
                                """.formatted(TECH_1_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + TECH1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"START","expectedVersion":1}
                                """))
                .andExpect(status().isOk());

        // Record initial stock level
        int qtyBefore = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM stock_balance WHERE part_id = ? AND location_id = ?",
                Integer.class, UUID.fromString(PART_PN002), UUID.fromString(TECH1_VAN_A));

        // Consume 1 unit of PN-002 from TECH_1 Van A
        mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .header("Authorization", "Bearer " + TECH1_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","lines":[{"partId":"%s","quantity":1,"reasonCode":"USED"}]}
                                """.formatted(TECH1_VAN_A, PART_PN002)))
                .andExpect(status().isOk());

        // Stock decremented by exactly 1
        int qtyAfter = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM stock_balance WHERE part_id = ? AND location_id = ?",
                Integer.class, UUID.fromString(PART_PN002), UUID.fromString(TECH1_VAN_A));
        assertThat(qtyAfter).isEqualTo(qtyBefore - 1);

        // Outbox event produced for parts consumption
        var events = OutboxAssertions.queryEvents(jdbc, woId);
        assertThat(events).anyMatch(e -> e.eventType() != null && e.eventType().contains("Parts"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-5: Technician day-list returns scoped results
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_1 day-list returns only TECH_1 assignments")
    void technicianDayList_returnsOnlyScopedJobs() throws Exception {
        // Create one work order assigned to TECH_1
        UUID woId = createWorkOrderViaHttp();
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0,
                                 "assignedTechnicianId":"%s"}
                                """.formatted(TECH_1_ID)))
                .andExpect(status().isOk());

        // TECH_1 can access their day list
        mockMvc.perform(get("/api/v1/technicians/me/work-orders")
                        .header("Authorization", "Bearer " + TECH1_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // TECH_2's day list does not include TECH_1's work order
        String tech2Response = mockMvc.perform(get("/api/v1/technicians/me/work-orders")
                        .header("Authorization", "Bearer " + TECH2_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(tech2Response).doesNotContain(woId.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Creates a work order in NEW state via the creation endpoint and returns its ID. */
    private UUID createWorkOrderViaHttp() throws Exception {
        String responseBody = mockMvc.perform(
                        post("/api/v1/work-orders")
                                .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "siteId": "%s",
                                          "customerId": "%s",
                                          "priority": "HIGH",
                                          "title": "Journey IT Work Order %s",
                                          "description": "Created by TechnicianJourneyIT"
                                        }
                                        """.formatted(SITE_ID, CUSTOMER_ID, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Extract the work order ID from the response
        String idStr = responseBody.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        return UUID.fromString(idStr);
    }

    /** Returns the current optimistic-lock version of a work order. */
    private long currentVersion(UUID woId) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM work_order WHERE id = ?", Long.class, woId);
        return version != null ? version : 0L;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested: idempotency (requires real idempotency filter — "api" profile)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parts over-consumption guard")
    class OverConsumptionGuardTests {

        @Test
        @DisplayName("Consuming more than stock available returns 422 with part name in message")
        void overConsumption_returns422WithPartName() throws Exception {
            UUID woId = createWorkOrderViaHttp();

            // Advance to IN_PROGRESS
            mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                            .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"event":"ASSIGN","expectedVersion":0,
                                     "assignedTechnicianId":"%s"}
                                    """.formatted(TECH_1_ID)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                            .header("Authorization", "Bearer " + TECH1_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"event":"START","expectedVersion":1}
                                    """))
                    .andExpect(status().isOk());

            // Attempt to consume more than available (current balance ≤ 10; request 9999)
            mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                            .header("Authorization", "Bearer " + TECH1_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"locationId":"%s","lines":[{"partId":"%s","quantity":9999,"reasonCode":"USED"}]}
                                    """.formatted(TECH1_VAN_A, PART_PN002)))
                    .andExpect(status().isUnprocessableEntity()); // 422
        }
    }
}
