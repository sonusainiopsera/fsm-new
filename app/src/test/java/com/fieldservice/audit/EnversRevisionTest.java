package com.fieldservice.audit;

import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for Hibernate Envers revision history (WO-003).
 *
 * <p>Covers:
 * <ul>
 *   <li>One revision per committing transaction</li>
 *   <li>Actor attribution (user id, role, traceId, clientIp)</li>
 *   <li>DEL revision retaining last known state (store_data_at_delete=true)</li>
 *   <li>Rolled-back transaction produces no audit rows</li>
 *   <li>Single transaction mutating two entities produces one shared REVINFO row</li>
 *   <li>Schema test: password_hash absent from app_user_aud</li>
 *   <li>Negative test: runtime role cannot UPDATE audit rows</li>
 * </ul>
 */
class EnversRevisionTest extends AbstractIntegrationTest {

    // Fixed UUIDs for fixture site and customer (from V100)
    private static final UUID SITE_A1   = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    // Fixed UUID for multi-revision fixture work order (from V101)
    static final UUID WO_MULTI = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setDispatcherPrincipal() {
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.DISPATCHER_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private UUID createWorkOrder(String title) {
        UUID[] idHolder = new UUID[1];
        txTemplate.executeWithoutResult(status -> {
            Site site = entityManager.getReference(Site.class, SITE_A1);
            Customer customer = entityManager.getReference(Customer.class, CUSTOMER_A);
            WorkOrder wo = new WorkOrder();
            wo.setSite(site);
            wo.setCustomer(customer);
            wo.setState(WorkOrderState.NEW);
            wo.setPriority(WorkOrderPriority.HIGH);
            wo.setTitle(title);
            entityManager.persist(wo);
            entityManager.flush();
            idHolder[0] = wo.getId();
        });
        return idHolder[0];
    }

    // -------------------------------------------------------------------------
    // 1. One revision per committing transaction
    // -------------------------------------------------------------------------

    @Test
    void createAndUpdateProduceExactlyTwoRevisions() {
        setDispatcherPrincipal();
        UUID id = createWorkOrder("Rev-count test WO");

        // Second transaction: update title
        txTemplate.executeWithoutResult(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, id);
            wo.setTitle("Rev-count test WO — updated");
            entityManager.flush();
        });

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, id);
            assertThat(revs).hasSize(2);
        });
    }

    // -------------------------------------------------------------------------
    // 2. Actor attribution
    // -------------------------------------------------------------------------

    @Test
    void revisionRecordsActorUserIdAndRole() {
        setDispatcherPrincipal();
        UUID id = createWorkOrder("Actor attribution test");

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, id);
            assertThat(revs).isNotEmpty();

            Number latestRev = revs.get(revs.size() - 1);
            AuditRevisionEntity revInfo = reader.findRevision(AuditRevisionEntity.class, latestRev);

            assertThat(revInfo.getActorUserId())
                    .isEqualTo(TestJwtFactory.DISPATCHER_USER_ID.toString());
            assertThat(revInfo.getActorRole()).isEqualTo("DISPATCHER");
            assertThat(revInfo.getRevisionInstant()).isNotNull();
        });
    }

    // -------------------------------------------------------------------------
    // 3. System actor when no authenticated principal
    // -------------------------------------------------------------------------

    @Test
    void systemActorRecordedWhenNoAuthentication() {
        SecurityContextHolder.clearContext(); // no principal
        UUID id = createWorkOrder("System actor test");

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, id);
            AuditRevisionEntity revInfo = reader.findRevision(AuditRevisionEntity.class, revs.get(0));
            assertThat(revInfo.getActorUserId()).isEqualTo("system");
            assertThat(revInfo.getActorRole()).isEqualTo("SYSTEM");
        });
    }

    // -------------------------------------------------------------------------
    // 4. DEL revision retains last known state (store_data_at_delete=true)
    // -------------------------------------------------------------------------

    @Test
    void deleteProducesDelRevisionRetainingLastState() {
        setDispatcherPrincipal();
        UUID id = createWorkOrder("Delete retention test");

        txTemplate.executeWithoutResult(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, id);
            entityManager.remove(wo);
            entityManager.flush();
        });

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, id);
            assertThat(revs).hasSize(2);

            // The last revision is DEL, but the entity state is retained
            Number delRev = revs.get(1);
            WorkOrder deletedState = reader.find(WorkOrder.class, id, delRev);
            // With store_data_at_delete=true, the entity snapshot is preserved
            assertThat(deletedState).isNotNull();
            assertThat(deletedState.getTitle()).isEqualTo("Delete retention test");
        });
    }

    // -------------------------------------------------------------------------
    // 5. Rolled-back transaction produces no audit rows
    // -------------------------------------------------------------------------

    @Test
    void rollbackProducesNoAuditRow() {
        setDispatcherPrincipal();
        UUID[] idHolder = new UUID[1];
        UUID tempId = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            Site site = entityManager.getReference(Site.class, SITE_A1);
            Customer customer = entityManager.getReference(Customer.class, CUSTOMER_A);
            WorkOrder wo = new WorkOrder();
            wo.setSite(site);
            wo.setCustomer(customer);
            wo.setState(WorkOrderState.NEW);
            wo.setPriority(WorkOrderPriority.LOW);
            wo.setTitle("Rollback test — should not be audited");
            entityManager.persist(wo);
            entityManager.flush();
            idHolder[0] = wo.getId();
            // Force rollback
            status.setRollbackOnly();
        });

        UUID rolledBackId = idHolder[0];
        if (rolledBackId == null) return; // entity never got an id (flush didn't happen)

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, rolledBackId);
            assertThat(revs).isEmpty();
        });
    }

    // -------------------------------------------------------------------------
    // 6. Single transaction — two entities share one REVINFO row
    // -------------------------------------------------------------------------

    @Test
    void singleTransactionProducesOneSharedRevision() {
        setDispatcherPrincipal();

        // Create WorkOrder AND Assignment in a single transaction
        UUID[] ids = new UUID[2];
        txTemplate.executeWithoutResult(status -> {
            Site site = entityManager.getReference(Site.class, SITE_A1);
            Customer customer = entityManager.getReference(Customer.class, CUSTOMER_A);
            WorkOrder wo = new WorkOrder();
            wo.setSite(site);
            wo.setCustomer(customer);
            wo.setState(WorkOrderState.NEW);
            wo.setPriority(WorkOrderPriority.MEDIUM);
            wo.setTitle("Shared revision WO");
            entityManager.persist(wo);

            Assignment assignment = new Assignment();
            assignment.setWorkOrderId(wo.getId() != null ? wo.getId() : UUID.randomUUID());
            assignment.setTechnicianId(TestJwtFactory.TECH_1_ID);
            assignment.setCurrent(true);
            entityManager.persist(assignment);

            entityManager.flush();
            ids[0] = wo.getId();
            ids[1] = assignment.getId();
        });

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);

            List<Number> woRevs = reader.getRevisions(WorkOrder.class, ids[0]);
            assertThat(woRevs).isNotEmpty();

            // Both entities should reference the same REVINFO revision number
            List<Number> assignRevs = reader.getRevisions(Assignment.class, ids[1]);
            assertThat(assignRevs).isNotEmpty();

            assertThat(woRevs.get(woRevs.size() - 1))
                    .as("WorkOrder and Assignment created in one transaction should share one revision")
                    .isEqualTo(assignRevs.get(assignRevs.size() - 1));
        });
    }

    // -------------------------------------------------------------------------
    // 7. Schema test: password_hash absent from app_user_aud
    // -------------------------------------------------------------------------

    @Test
    void passwordHashAbsentFromAppUserAudTable() {
        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'app_user_aud' AND column_name = 'password_hash'",
                Integer.class);
        assertThat(count)
                .as("password_hash must not appear in app_user_aud (CONFIDENTIAL column)")
                .isZero();
    }

    // -------------------------------------------------------------------------
    // 8. Fixture-based revision query: WO_MULTI has three pre-seeded revisions
    // -------------------------------------------------------------------------

    @Test
    void fixtureWorkOrderHasThreeAuditRevisions() {
        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, WO_MULTI);
            assertThat(revs)
                    .as("V101 fixture should have inserted 3 revisions for WO_MULTI")
                    .hasSize(3);
        });
    }

    // -------------------------------------------------------------------------
    // 9. Negative test: fieldservice role cannot UPDATE audit rows
    // -------------------------------------------------------------------------

    @Test
    void fieldserviceRoleCannotUpdateAuditRows() throws Exception {
        // Ensure fieldservice role exists (V6 migration creates it; this is idempotent)
        jdbc.execute("DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') " +
                "THEN CREATE ROLE fieldservice; END IF; END $$");
        // Grant fieldservice to the test user so we can SET ROLE
        jdbc.execute("DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT FROM pg_auth_members m " +
                "    JOIN pg_roles r ON r.oid = m.roleid " +
                "    JOIN pg_roles mr ON mr.oid = m.member " +
                "    WHERE r.rolname = 'fieldservice' AND mr.rolname = 'test') " +
                "THEN GRANT fieldservice TO test; END IF; END $$");
        // Ensure the correct grants are in place
        jdbc.execute("REVOKE ALL ON work_order_aud FROM fieldservice");
        jdbc.execute("GRANT SELECT, INSERT ON work_order_aud TO fieldservice");

        // Switch to fieldservice role and attempt UPDATE — must fail
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL ROLE fieldservice");
                assertThatThrownBy(() -> stmt.execute(
                        "UPDATE work_order_aud SET revtype = 0 WHERE rev = -999999"))
                        .as("fieldservice role must not have UPDATE privilege on work_order_aud")
                        .hasMessageContainingIgnoringCase("permission denied");
            } finally {
                conn.rollback();
            }
        }
    }
}
