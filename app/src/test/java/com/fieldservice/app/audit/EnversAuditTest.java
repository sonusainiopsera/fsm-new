package com.fieldservice.app.audit;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.RevisionDto;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRevisionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Envers audit feature (WO-003).
 *
 * Covers:
 *  AC-2: startup with ddl-auto=validate succeeds
 *  AC-3: actor attribution is populated
 *  AC-4: password_hash absent from app_user_aud
 *  AC-5: one revision per transaction
 *  AC-6: runtime role cannot UPDATE audit rows
 *  AC-7: delete revision retention and rollback produces no rows
 *  AC-8: revisions endpoint pagination and role access
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Envers audit integration tests")
class EnversAuditTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private WorkOrderRevisionService revisionService;

    @Autowired
    private MockMvc mockMvc;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private static final UUID SHARED_CUSTOMER_ID = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID SHARED_SITE_ID     = UUID.fromString("a2000000-0000-0000-0000-000000000001");

    @BeforeEach
    void ensureSharedFixtures() {
        jdbc.update(
            "INSERT INTO customer (id, name, is_active, created_at, updated_at) " +
            "VALUES (?, 'Audit Test Customer', true, now(), now()) ON CONFLICT (id) DO NOTHING",
            SHARED_CUSTOMER_ID);
        jdbc.update(
            "INSERT INTO site (id, name, customer_id, created_at) " +
            "VALUES (?, 'Audit Test Site', ?, now()) ON CONFLICT (id) DO NOTHING",
            SHARED_SITE_ID, SHARED_CUSTOMER_ID);
    }

    // ── AC-2: startup validation ───────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("AC-2: ddl-auto=validate passes — Flyway audit tables match Envers metadata")
    void startup_validate_succeeds() {
        // If the Spring context started without exception, ddl-auto=validate passed.
        // Additionally assert that the expected audit tables exist.
        for (String table : new String[]{
                "revinfo", "work_order_aud", "assignment_aud",
                "technician_certification_aud", "app_user_aud", "site_aud", "sla_policy_aud"}) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
            assertThat(count).as("Audit table '%s' must exist", table).isEqualTo(1);
        }
    }

    // ── AC-3: actor attribution ────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("AC-3: creating a work order records actor_user_id and actor_role in REVINFO")
    void create_work_order_records_actor_attribution() {
        setAdminAuth("audit-actor-test");

        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Attribution Test WO", site, "HIGH");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });

        UUID woId = woIdRef.get();
        Map<String, Object> revInfo = jdbc.queryForMap(
            "SELECT r.actor_user_id, r.actor_role " +
            "FROM revinfo r JOIN work_order_aud w ON w.rev = r.rev " +
            "WHERE w.id = ? ORDER BY r.rev DESC LIMIT 1",
            woId);

        assertThat(revInfo.get("actor_user_id")).isEqualTo("audit-actor-test");
        assertThat(revInfo.get("actor_role")).asString().contains("ADMIN");
    }

    @Test
    @Order(3)
    @DisplayName("AC-3: unauthenticated write records 'system' actor")
    void unauthenticated_write_records_system_actor() {
        SecurityContextHolder.clearContext();  // no authentication

        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("System Actor Test WO", site, "LOW");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });

        Map<String, Object> revInfo = jdbc.queryForMap(
            "SELECT r.actor_user_id, r.actor_role " +
            "FROM revinfo r JOIN work_order_aud w ON w.rev = r.rev " +
            "WHERE w.id = ? ORDER BY r.rev DESC LIMIT 1",
            woIdRef.get());

        assertThat(revInfo.get("actor_user_id")).isEqualTo("system");
        assertThat(revInfo.get("actor_role")).isEqualTo("SYSTEM");
    }

    // ── AC-4: password_hash excluded from app_user_aud ────────────────────

    @Test
    @Order(4)
    @DisplayName("AC-4: password_hash column is absent from app_user_aud")
    void password_hash_absent_from_app_user_aud() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_name = 'app_user_aud' AND column_name = 'password_hash'",
            Integer.class);
        assertThat(count).as("password_hash must NOT appear in app_user_aud").isZero();
    }

    // ── AC-5: one revision per transaction ────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("AC-5: updating two entities in one transaction produces one shared REVINFO row")
    void multi_entity_single_transaction_shares_one_revinfo_row() {
        setAdminAuth("multi-entity-user");

        // Step 1: create work order (separate tx — known rev count baseline)
        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Multi-Entity WO", site, "MEDIUM");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });

        // Step 2: update work order AND create assignment in the same transaction
        AtomicReference<UUID> assignIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            WorkOrder wo = em.find(WorkOrder.class, woIdRef.get());
            wo.assign("tech-multi-1");
            Assignment assign = new Assignment(wo, "tech-multi-1");
            em.persist(assign);
            assignIdRef.set(assign.getId());
        });

        // Assert: the most recent work_order_aud row and the assignment_aud row share rev
        Number woRev = jdbc.queryForObject(
            "SELECT rev FROM work_order_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
            Number.class, woIdRef.get());
        Number assignRev = jdbc.queryForObject(
            "SELECT rev FROM assignment_aud WHERE id = ?",
            Number.class, assignIdRef.get());

        assertThat(assignRev.longValue())
            .as("assignment and work_order update must share one REVINFO row")
            .isEqualTo(woRev.longValue());
    }

    // ── AC-6: runtime role cannot UPDATE ──────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("AC-6: fieldservice_runtime role cannot UPDATE audit rows")
    void runtime_role_cannot_update_audit_row() {
        // Ensure at least one audit row exists
        setAdminAuth("perm-test-user");
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            em.persist(new WorkOrder("Perm Test WO", site, "LOW"));
        });

        // Create a login role that only has fieldservice_runtime privileges
        jdbc.execute("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT FROM pg_user WHERE usename = 'fs_rt_test') THEN
                    CREATE USER fs_rt_test WITH PASSWORD 'test';
                END IF;
            END $$
            """);
        jdbc.execute("GRANT fieldservice_runtime TO fs_rt_test");

        // Connect as the runtime-only user
        DriverManagerDataSource runtimeDs = new DriverManagerDataSource();
        runtimeDs.setUrl(datasourceUrl);
        runtimeDs.setUsername("fs_rt_test");
        runtimeDs.setPassword("test");

        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDs);

        // UPDATE must be denied — fieldservice_runtime has no UPDATE grant
        assertThatThrownBy(() ->
            runtimeJdbc.execute("UPDATE work_order_aud SET revtype = 0 WHERE 1=1"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ── AC-7: delete revision and rollback ────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("AC-7: deleting an entity produces a DEL revision retaining last state")
    void delete_produces_del_revision_with_last_state() {
        setAdminAuth("delete-test-user");

        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Delete Test WO", site, "HIGH");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });

        // Update it
        runInCommittedTx(() -> {
            WorkOrder wo = em.find(WorkOrder.class, woIdRef.get());
            wo.setTitle("Delete Test WO (updated)");
        });

        // Delete it
        runInCommittedTx(() -> {
            WorkOrder wo = em.find(WorkOrder.class, woIdRef.get());
            em.remove(wo);
        });

        // Assert 3 revisions: ADD, MOD, DEL
        Integer revCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM work_order_aud WHERE id = ?",
            Integer.class, woIdRef.get());
        assertThat(revCount).isEqualTo(3);

        // Last revision must be type 2 (DEL)
        Integer delRevType = jdbc.queryForObject(
            "SELECT revtype FROM work_order_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
            Integer.class, woIdRef.get());
        assertThat(delRevType).as("Last revision type must be DEL (2)").isEqualTo(2);

        // DEL revision retains last known state (store_data_at_delete=true)
        String titleAtDel = jdbc.queryForObject(
            "SELECT title FROM work_order_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
            String.class, woIdRef.get());
        assertThat(titleAtDel).isEqualTo("Delete Test WO (updated)");
    }

    @Test
    @Order(8)
    @DisplayName("AC-7: rolled-back transaction produces no audit or REVINFO rows")
    void rollback_produces_no_audit_rows() {
        setAdminAuth("rollback-test-user");

        AtomicReference<UUID> rolledBackIdRef = new AtomicReference<>();

        // Force rollback after flushing so Envers processes the insert event
        new TransactionTemplate(txManager).execute(status -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Rollback Test WO", site, "LOW");
            em.persist(wo);
            em.flush();  // force Envers to process — the subsequent rollback must undo it
            rolledBackIdRef.set(wo.getId());
            status.setRollbackOnly();
            return null;
        });

        // No audit row must exist for this work order
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM work_order_aud WHERE id = ?",
            Integer.class, rolledBackIdRef.get());
        assertThat(auditCount).as("Rolled-back transaction must produce no audit rows").isZero();
    }

    // ── AC-8: revision query endpoint ─────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("AC-8: revisions endpoint returns paginated history descending")
    void revisions_endpoint_returns_paginated_revisions() throws Exception {
        setAdminAuth("endpoint-test-user");

        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Endpoint Test WO", site, "HIGH");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });
        runInCommittedTx(() -> {
            WorkOrder wo = em.find(WorkOrder.class, woIdRef.get());
            wo.setTitle("Endpoint Test WO (v2)");
        });

        mockMvc.perform(get("/api/v1/audit/work-orders/{id}/revisions", woIdRef.get())
                .with(user("endpoint-test-user").roles("ADMIN"))
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content[0].revisionType").value("UPDATE"))
            .andExpect(jsonPath("$.content[1].revisionType").value("CREATE"));
    }

    @Test
    @Order(10)
    @DisplayName("AC-8: revisions endpoint denies access to CUSTOMER role")
    void revisions_endpoint_denies_customer_role() throws Exception {
        UUID anyId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/audit/work-orders/{id}/revisions", anyId)
                .with(user("cust-user").roles("CUSTOMER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    @DisplayName("AC-8: revisions endpoint returns empty page for unknown work order")
    void revisions_endpoint_empty_for_unknown_id() throws Exception {
        mockMvc.perform(get("/api/v1/audit/work-orders/{id}/revisions", UUID.randomUUID())
                .with(user("admin-user").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(12)
    @DisplayName("AC-9/10: WorkOrderRevisionService returns diffs with before/after values")
    void revision_service_returns_field_diffs() {
        setAdminAuth("diff-test-user");

        AtomicReference<UUID> woIdRef = new AtomicReference<>();
        runInCommittedTx(() -> {
            Site site = em.getReference(Site.class, SHARED_SITE_ID);
            WorkOrder wo = new WorkOrder("Diff Test WO", site, "HIGH");
            em.persist(wo);
            woIdRef.set(wo.getId());
        });
        runInCommittedTx(() -> {
            WorkOrder wo = em.find(WorkOrder.class, woIdRef.get());
            wo.setTitle("Diff Test WO (updated)");
        });

        Page<RevisionDto> page = revisionService.getRevisions(
            woIdRef.get(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        // First result is most recent (UPDATE)
        RevisionDto update = page.getContent().get(0);
        assertThat(update.revisionType()).isEqualTo("UPDATE");
        assertThat(update.actorUserId()).isEqualTo("diff-test-user");
        // Diff should contain the title change
        assertThat(update.changes())
            .anyMatch(c -> c.field().equals("title")
                && "Diff Test WO".equals(c.before())
                && "Diff Test WO (updated)".equals(c.after()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setAdminAuth(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private void runInCommittedTx(Runnable action) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> action.run());
    }
}
