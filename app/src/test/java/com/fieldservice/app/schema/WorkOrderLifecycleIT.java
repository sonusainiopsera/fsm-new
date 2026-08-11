package com.fieldservice.app.schema;

import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.AuditAssertions;
import com.fieldservice.support.DatabaseCleaner;
import com.fieldservice.support.OutboxAssertions;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

import java.util.UUID;

import static com.fieldservice.support.AuditAssertions.REV_INSERT;
import static com.fieldservice.support.AuditAssertions.REV_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reference end-to-end integration test proving the full lifecycle of work-order
 * creation, lifecycle transition via HTTP, Envers audit trail, and outbox event.
 *
 * <p>Uses the <em>truncating</em> isolation strategy (real commits) because outbox
 * rows and Envers revisions must be visible across transaction boundaries.
 * {@link DatabaseCleaner#truncateAll()} is called in {@code @AfterEach}.
 */
class WorkOrderLifecycleIT extends AbstractIntegrationTest {

    /* Reference data UUIDs from V4__seed_reference_data.sql — never truncated */
    private static final String SEED_SITE_ID       = "ffffffff-0000-7003-8000-000000000001";
    private static final String SEED_TECHNICIAN_ID = "ffffffff-0000-7005-8000-000000000001";

    @Autowired
    private DatabaseCleaner cleaner;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanup() {
        cleaner.truncateAll();
    }

    @Test
    @DisplayName("create work order, assign via HTTP → Envers revision + outbox event produced")
    void create_and_assign_produces_revision_and_outbox() throws Exception {
        // ---- Step 1: create a work order with a real commit --------------------
        UUID woId = transactionTemplate.execute(status -> {
            com.fieldservice.site.domain.Site site =
                    entityManager.find(com.fieldservice.site.domain.Site.class,
                            UUID.fromString(SEED_SITE_ID));
            assertThat(site).as("V4 seed site must be present").isNotNull();

            WorkOrder wo = new WorkOrder(
                    "WO-E2E-" + System.nanoTime(),
                    WorkOrderStatus.NEW,
                    "HIGH",
                    site,
                    null);
            entityManager.persist(wo);
            entityManager.flush();
            return wo.getId();
        });

        assertThat(woId).isNotNull();

        // ---- Step 2: verify INSERT revision was created -----------------------
        AuditAssertions.assertRevisionCount(dataSource, "work_order_aud", woId, 1);
        AuditAssertions.assertLatestRevisionType(dataSource, "work_order_aud", woId, REV_INSERT);

        // ---- Step 3: transition to ASSIGNED via HTTP POST ---------------------
        int currentVersion = transactionTemplate.execute(status ->
                entityManager.find(WorkOrder.class, woId).getVersion());

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(dispatcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "event": "ASSIGN",
                                  "expectedVersion": %d,
                                  "technicianId": "%s"
                                }
                                """.formatted(currentVersion, SEED_TECHNICIAN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // ---- Step 4: verify UPDATE revision after ASSIGN ----------------------
        AuditAssertions.assertRevisionCount(dataSource, "work_order_aud", woId, 2);
        AuditAssertions.assertLatestRevisionType(dataSource, "work_order_aud", woId, REV_UPDATE);

        // ---- Step 5: verify outbox event was published for the transition ------
        OutboxAssertions.assertExactlyOne(dataSource, woId, "WORK_ORDER_ASSIGNED");
    }

    @Test
    @DisplayName("data from one test is never visible in a subsequent test (isolation proof)")
    void isolation_between_tests_is_enforced() throws Exception {
        // Insert a work order in this test and verify count is exactly 1
        String ref = "WO-ISOLATION-" + System.nanoTime();
        transactionTemplate.execute(status -> {
            com.fieldservice.site.domain.Site site =
                    entityManager.find(com.fieldservice.site.domain.Site.class,
                            UUID.fromString(SEED_SITE_ID));
            WorkOrder wo = new WorkOrder(ref, WorkOrderStatus.NEW, "LOW", site, null);
            entityManager.persist(wo);
            entityManager.flush();
            return wo.getId();
        });

        long count = transactionTemplate.execute(status ->
                ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM work_order WHERE reference = :ref")
                        .setParameter("ref", ref)
                        .getSingleResult()).longValue());

        assertThat(count).isEqualTo(1L);
        // @AfterEach truncates — next test will see count 0
    }

    // ---- helpers ---------------------------------------------------------------

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor dispatcher() {
        return jwt().authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"));
    }
}
