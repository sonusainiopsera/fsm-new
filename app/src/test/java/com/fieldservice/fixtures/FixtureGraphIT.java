package com.fieldservice.fixtures;

import com.fieldservice.domain.assignment.Assignment;
import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockLocation;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.technician.Technician;
import com.fieldservice.domain.technician.TechnicianCertification;
import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.security.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that persists the full WO-201 fixture graph through the repository layer
 * and asserts all foreign keys, CHECK constraints, and NOT NULL constraints are satisfied.
 *
 * <p>Also proves:
 * <ul>
 *   <li>Seed SQL idempotency: loaded twice without duplicate-row errors.</li>
 *   <li>BCrypt hash verification: stored hash matches TEST_PASSWORD via PasswordEncoder.</li>
 *   <li>Determinism: two builds of the same scenario produce identical IDs.</li>
 * </ul>
 */
@DisplayName("Fixture graph integration tests (WO-201)")
@Sql(scripts = "classpath:fixtures/seed-core.sql")
class FixtureGraphIT extends AbstractIntegrationTest {

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;
    @Autowired PasswordEncoder passwordEncoder;

    // -----------------------------------------------------------------------
    // Seed SQL idempotency
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("seed-core.sql SLA policy rows are present and correct")
    void seedCoreSql_slaPolicyRowsPresent() {
        Long count = entityManager.createQuery(
                "SELECT COUNT(s) FROM SlaPolicy s WHERE s.effectiveTo IS NULL", Long.class)
                .getSingleResult();
        // Seed adds 4 rows; V100 may add none; total ≥ 4
        assertThat(count).isGreaterThanOrEqualTo(4L);
    }

    @Test
    @DisplayName("seed-core.sql is idempotent: second execution produces no extra rows")
    @Sql(scripts = {"classpath:fixtures/seed-core.sql", "classpath:fixtures/seed-core.sql"})
    void seedCoreSql_idempotent_noDuplicateRows() {
        // Seed-core defines 4 SLA rows with fixed IDs; two executions must still yield 4 seed rows.
        // Query by well-known seed ID to avoid counting V100 rows.
        UUID seedSlaId = UUID.fromString("ff000000-0000-0000-0000-000000000001");
        Long count = entityManager.createQuery(
                "SELECT COUNT(s) FROM SlaPolicy s WHERE s.id = :id OR s.id = :id2 OR s.id = :id3 OR s.id = :id4",
                Long.class)
                .setParameter("id",  UUID.fromString("ff000000-0000-0000-0000-000000000001"))
                .setParameter("id2", UUID.fromString("ff000000-0000-0000-0000-000000000002"))
                .setParameter("id3", UUID.fromString("ff000000-0000-0000-0000-000000000003"))
                .setParameter("id4", UUID.fromString("ff000000-0000-0000-0000-000000000004"))
                .getSingleResult();
        assertThat(count).isEqualTo(4L);
    }

    // -----------------------------------------------------------------------
    // Full fixture graph persist
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full dispatch-ready scenario persists without constraint violations")
    void dispatchReadyScenario_persistsClean() {
        ScenarioFixtures.DispatchReadyScenario scenario = ScenarioFixtures.dispatchReady();

        UUID[] customerId = {null};
        UUID[] siteId = {null};
        UUID[] partId = {null};
        UUID[] warehouseId = {null};
        UUID[] emptyLocId = {null};

        // Persist root aggregates
        txTemplate.executeWithoutResult(status -> {
            entityManager.persist(scenario.customer());
            entityManager.persist(scenario.site());
            entityManager.persist(scenario.asset());
            entityManager.flush();

            customerId[0] = scenario.customer().getId();
            siteId[0] = scenario.site().getId();
        });

        // Persist users, role assignments, technicians, certifications
        txTemplate.executeWithoutResult(status -> {
            for (ScenarioFixtures.TechnicianProfile profile : scenario.technicianPool()) {
                entityManager.persist(profile.user());
                entityManager.persist(profile.roleAssignment());
                entityManager.persist(profile.technician());
                for (TechnicianCertification cert : profile.certifications()) {
                    entityManager.persist(cert);
                }
            }
            entityManager.persist(scenario.dispatcherUser());
            entityManager.persist(scenario.dispatcherRole());
            entityManager.flush();
        });

        // Persist work order (re-attach managed entities)
        txTemplate.executeWithoutResult(status -> {
            Customer managedCustomer = entityManager.find(Customer.class, customerId[0]);
            Site managedSite = entityManager.find(Site.class, siteId[0]);
            WorkOrder wo = scenario.workOrder();
            wo.setCustomer(managedCustomer);
            wo.setSite(managedSite);
            entityManager.persist(wo);
            entityManager.flush();
        });

        // Persist inventory
        txTemplate.executeWithoutResult(status -> {
            entityManager.persist(scenario.hvacPart());
            entityManager.persist(scenario.warehouse());
            entityManager.persist(scenario.emptyLocation());
            entityManager.flush();

            partId[0] = scenario.hvacPart().getId();
            warehouseId[0] = scenario.warehouse().getId();
            emptyLocId[0] = scenario.emptyLocation().getId();
        });

        // Persist stock balances
        txTemplate.executeWithoutResult(status -> {
            StockBalance stocked = scenario.stockedBalance(partId[0], warehouseId[0]);
            StockBalance empty = scenario.zeroBalance(partId[0], emptyLocId[0]);
            entityManager.persist(stocked);
            entityManager.persist(empty);
            entityManager.flush();
        });

        // Assert all entities retrievable
        entityManager.clear();
        txTemplate.executeWithoutResult(status -> {
            assertThat(entityManager.find(Customer.class, customerId[0])).isNotNull();
            assertThat(entityManager.find(Site.class, siteId[0])).isNotNull();
            assertThat(entityManager.find(Part.class, partId[0])).isNotNull();
            StockBalance stocked = (StockBalance) entityManager
                    .createQuery("SELECT sb FROM StockBalance sb WHERE sb.locationId = :loc AND sb.quantityOnHand > 0")
                    .setParameter("loc", warehouseId[0])
                    .getSingleResult();
            assertThat(stocked.getQuantityOnHand()).isEqualTo(10);
            StockBalance empty = (StockBalance) entityManager
                    .createQuery("SELECT sb FROM StockBalance sb WHERE sb.locationId = :loc AND sb.quantityOnHand = 0")
                    .setParameter("loc", emptyLocId[0])
                    .getSingleResult();
            assertThat(empty.getQuantityOnHand()).isZero();
        });
    }

    // -----------------------------------------------------------------------
    // All lifecycle states persist
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All eight work order states persist without constraint violations")
    void allStatesScenario_allStatesPresistClean() {
        ScenarioFixtures.AllStatesScenario scenario = ScenarioFixtures.allWorkOrderStates();

        UUID[] customerId = {null};
        UUID[] siteId = {null};

        txTemplate.executeWithoutResult(status -> {
            entityManager.persist(scenario.customer());
            entityManager.persist(scenario.site());
            entityManager.flush();
            customerId[0] = scenario.customer().getId();
            siteId[0] = scenario.site().getId();
        });

        txTemplate.executeWithoutResult(status -> {
            Customer managedCustomer = entityManager.find(Customer.class, customerId[0]);
            Site managedSite = entityManager.find(Site.class, siteId[0]);

            for (WorkOrderFixtures.WorkOrderResult result : scenario.workOrderResults()) {
                WorkOrder wo = result.workOrder();
                wo.setCustomer(managedCustomer);
                wo.setSite(managedSite);
                entityManager.persist(wo);
            }
            entityManager.flush();
        });

        entityManager.clear();

        // Assert one WO per state is persisted and retrievable
        for (WorkOrderState state : WorkOrderState.values()) {
            Long count = entityManager.createQuery(
                    "SELECT COUNT(w) FROM WorkOrder w WHERE w.state = :state AND w.customerId = :cid",
                    Long.class)
                    .setParameter("state", state)
                    .setParameter("cid", customerId[0])
                    .getSingleResult();
            assertThat(count)
                    .as("Expected one work order in state %s", state)
                    .isEqualTo(1L);
        }
    }

    // -----------------------------------------------------------------------
    // BCrypt hash verification
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Persisted AppUser password hash verifies with TEST_PASSWORD")
    void persistedUser_passwordHashVerifies() {
        UUID[] userId = {null};

        txTemplate.executeWithoutResult(status -> {
            AppUser user = UserFixtures.dispatcher()
                    .withEmail("bcrypt.verify." + UUID.randomUUID() + "@example.com")
                    .build();
            entityManager.persist(user);
            entityManager.flush();
            userId[0] = user.getId();
        });

        entityManager.clear();

        txTemplate.executeWithoutResult(status -> {
            AppUser loaded = entityManager.find(AppUser.class, userId[0]);
            assertThat(loaded).isNotNull();
            assertThat(passwordEncoder.matches(UserFixtures.TEST_PASSWORD, loaded.getPasswordHash()))
                    .as("Password hash must verify with TEST_PASSWORD using DelegatingPasswordEncoder")
                    .isTrue();
        });
    }

    // -----------------------------------------------------------------------
    // Determinism
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two dispatch-ready scenario builds from reset produce identical entity IDs")
    void determinism_twoDispatchReadyBuilds_identicalIds() {
        ScenarioFixtures.DispatchReadyScenario s1 = ScenarioFixtures.dispatchReady();
        ScenarioFixtures.DispatchReadyScenario s2 = ScenarioFixtures.dispatchReady();

        assertThat(s1.customer().getId()).isEqualTo(s2.customer().getId());
        assertThat(s1.site().getId()).isEqualTo(s2.site().getId());
        assertThat(s1.workOrder().getId()).isEqualTo(s2.workOrder().getId());
        assertThat(s1.dispatcherUser().getId()).isEqualTo(s2.dispatcherUser().getId());
    }

    // -----------------------------------------------------------------------
    // Stock balance constraints
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Zero stock balance satisfies DB CHECK constraint (quantity_on_hand >= 0)")
    void zeroBalance_satisfiesCheckConstraint() {
        UUID partId = UUID.fromString("50000000-0000-0000-0000-000000000001"); // from V100
        UUID locationId = UUID.fromString("60000000-0000-0000-0000-000000000001"); // from V100

        // Use unique IDs to avoid PK conflict
        txTemplate.executeWithoutResult(status -> {
            Part part = InventoryFixtures.activePart()
                    .withPartNumber("PN-ZERO-" + UUID.randomUUID())
                    .withSku("SKU-ZERO-" + UUID.randomUUID())
                    .build();
            entityManager.persist(part);
            entityManager.flush();

            StockLocation loc = InventoryFixtures.warehouseLocation()
                    .withName("Zero-stock test location " + UUID.randomUUID())
                    .build();
            entityManager.persist(loc);
            entityManager.flush();

            StockBalance zero = InventoryFixtures.zeroBalance(part.getId(), loc.getId());
            entityManager.persist(zero);
            entityManager.flush();

            assertThat(zero.getQuantityOnHand()).isZero();
        });
    }
}
