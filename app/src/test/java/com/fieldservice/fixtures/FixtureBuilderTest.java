package com.fieldservice.fixtures;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.site.domain.Site;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for every builder in the fixture library.
 *
 * <p>Verifies default validity, override semantics, and lifecycle consistency.
 * No Spring context; no database.
 */
class FixtureBuilderTest {

    @BeforeEach
    void resetIds() {
        DeterministicIds.reset();
    }

    // ---- DeterministicIds ---------------------------------------------------

    @Nested
    @DisplayName("DeterministicIds")
    class DeterministicIdsTests {

        @Test
        @DisplayName("fixed clock returns constant instant")
        void fixedClockIsConstant() {
            assertThat(DeterministicIds.CLOCK.instant()).isEqualTo(DeterministicIds.FIXED_INSTANT);
        }

        @Test
        @DisplayName("next() returns distinct UUIDs")
        void nextProducesDistinctUuids() {
            UUID a = DeterministicIds.next();
            UUID b = DeterministicIds.next();
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("reset() restarts the sequence — two equal builds produce identical UUIDs")
        void resetMakesSequenceReproducible() {
            UUID first  = DeterministicIds.next();
            DeterministicIds.reset();
            UUID second = DeterministicIds.next();
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("UUIDs are time-ordered (monotonically increasing)")
        void uuidsAreMonotonic() {
            UUID a = DeterministicIds.next();
            UUID b = DeterministicIds.next();
            assertThat(a.compareTo(b)).isNegative();
        }
    }

    // ---- UserFixtures -------------------------------------------------------

    @Nested
    @DisplayName("UserFixtures")
    class UserFixturesTests {

        @Test
        @DisplayName("admin builder produces valid active user with expected email")
        void adminBuilderDefaults() {
            AppUser user = UserFixtures.admin().build();
            assertThat(user.getId()).isNotNull();
            assertThat(user.getEmail()).isEqualTo("admin@example.local");
            assertThat(user.getActive()).isTrue();
            assertThat(user.getPasswordHash()).isEqualTo(UserFixtures.BCRYPT_HASH_COST12);
            assertThat(user.getPasswordHash().length()).isLessThanOrEqualTo(72);
        }

        @Test
        @DisplayName("each role builder uses the reserved example.local domain")
        void allBuildersUseReservedDomain() {
            assertThat(UserFixtures.admin().build().getEmail()).endsWith("@example.local");
            assertThat(UserFixtures.dispatcher().build().getEmail()).endsWith("@example.local");
            assertThat(UserFixtures.technician().build().getEmail()).endsWith("@example.local");
            assertThat(UserFixtures.manager().build().getEmail()).endsWith("@example.local");
            assertThat(UserFixtures.customer().build().getEmail()).endsWith("@example.local");
        }

        @Test
        @DisplayName("inactive builder produces an inactive user")
        void inactiveBuilder() {
            AppUser user = UserFixtures.inactive().build();
            assertThat(user.getActive()).isFalse();
        }

        @Test
        @DisplayName("withId override replaces the generated ID")
        void idOverride() {
            UUID fixed = UUID.fromString("00000000-0000-0000-0000-000000000001");
            AppUser user = UserFixtures.admin().withId(fixed).build();
            assertThat(user.getId()).isEqualTo(fixed);
        }

        @Test
        @DisplayName("roleFor builder produces a role assignment for the user")
        void roleAssignmentBuilder() {
            AppUser user = UserFixtures.technician().build();
            RoleAssignment ra = UserFixtures.roleFor(user, AppRole.TECHNICIAN).build();
            assertThat(ra.getId()).isNotNull();
            assertThat(ra.getUser().getId()).isEqualTo(user.getId());
            assertThat(ra.getRoleName()).isEqualTo(AppRole.TECHNICIAN);
        }
    }

    // ---- TechnicianFixtures -------------------------------------------------

    @Nested
    @DisplayName("TechnicianFixtures")
    class TechnicianFixturesTests {

        @Test
        @DisplayName("defaults builder produces a technician with 555 phone prefix")
        void defaultsBuilderPhone() {
            UUID userId = DeterministicIds.next();
            Technician t = TechnicianFixtures.defaults(userId).build();
            assertThat(t.getId()).isNotNull();
            assertThat(t.getUserId()).isEqualTo(userId);
            assertThat(t.getPhone()).startsWith("555");
        }

        @Test
        @DisplayName("named builder sets custom full name")
        void namedBuilder() {
            UUID userId = DeterministicIds.next();
            Technician t = TechnicianFixtures.named(userId, "Jane Field").build();
            assertThat(t.getFullName()).isEqualTo("Jane Field");
        }

        @Test
        @DisplayName("validCertification produces cert expiring in the future")
        void validCertification() {
            UUID techId = DeterministicIds.next();
            TechnicianCertification cert = TechnicianFixtures.validCertification(techId).build();
            assertThat(cert.getExpiresAt()).isAfter(DeterministicIds.FIXED_INSTANT);
        }

        @Test
        @DisplayName("expiredCertification produces cert expired before fixed instant")
        void expiredCertification() {
            UUID techId = DeterministicIds.next();
            TechnicianCertification cert = TechnicianFixtures.expiredCertification(techId).build();
            assertThat(cert.getExpiresAt()).isBefore(DeterministicIds.FIXED_INSTANT);
        }

        @Test
        @DisplayName("nonExpiringCertification has null expiresAt")
        void nonExpiringCertification() {
            UUID techId = DeterministicIds.next();
            TechnicianCertification cert = TechnicianFixtures.nonExpiringCertification(techId).build();
            assertThat(cert.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("boundaryExpiredCertification expires exactly at fixed instant")
        void boundaryExpiredCertification() {
            UUID techId = DeterministicIds.next();
            TechnicianCertification cert = TechnicianFixtures.boundaryExpiredCertification(techId).build();
            assertThat(cert.getExpiresAt()).isEqualTo(DeterministicIds.FIXED_INSTANT);
        }
    }

    // ---- WorkOrderFixtures --------------------------------------------------

    @Nested
    @DisplayName("WorkOrderFixtures")
    class WorkOrderFixturesTests {

        private Site site;

        @BeforeEach
        void setupSite() {
            site = new Site(DeterministicIds.next(), "Test Site",
                    DeterministicIds.next());
        }

        @Test
        @DisplayName("newOrder produces a NEW work order with no technician")
        void newOrder() {
            WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.newOrder(site);
            assertThat(g.workOrder().getState()).isEqualTo(WorkOrderStatus.NEW);
            assertThat(g.workOrder().getAssignedTechnicianId()).isNull();
            assertThat(g.hasAssignment()).isFalse();
        }

        @Test
        @DisplayName("assigned work order carries an assignment with no releasedAt")
        void assignedOrderHasAssignment() {
            UUID techId = DeterministicIds.next();
            WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.assigned(site, techId);
            assertThat(g.workOrder().getState()).isEqualTo(WorkOrderStatus.ASSIGNED);
            assertThat(g.workOrder().getAssignedTechnicianId()).isEqualTo(techId);
            assertThat(g.hasAssignment()).isTrue();
            assertThat(g.assignment().getReleasedAt()).isNull();
        }

        @Test
        @DisplayName("completed work order carries an assignment with releasedAt (logged labour)")
        void completedOrderHasReleasedAssignment() {
            UUID techId = DeterministicIds.next();
            WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.completed(site, techId);
            assertThat(g.workOrder().getState()).isEqualTo(WorkOrderStatus.COMPLETED);
            assertThat(g.hasAssignment()).isTrue();
            assertThat(g.assignment().getReleasedAt()).isNotNull()
                    .isAfter(g.assignment().getAssignedAt());
        }

        @Test
        @DisplayName("closed work order carries an assignment with releasedAt")
        void closedOrderHasReleasedAssignment() {
            UUID techId = DeterministicIds.next();
            WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.closed(site, techId);
            assertThat(g.workOrder().getState()).isEqualTo(WorkOrderStatus.CLOSED);
            assertThat(g.hasAssignment()).isTrue();
            assertThat(g.assignment().getReleasedAt()).isNotNull();
        }

        @Test
        @DisplayName("cancelled work order has no assignment")
        void cancelledHasNoAssignment() {
            WorkOrderFixtures.WorkOrderGraph g = WorkOrderFixtures.cancelled(site);
            assertThat(g.workOrder().getState()).isEqualTo(WorkOrderStatus.CANCELLED);
            assertThat(g.hasAssignment()).isFalse();
        }

        @Test
        @DisplayName("builder can produce all eight lifecycle states")
        void allEightStates() {
            UUID techId = DeterministicIds.next();
            assertThat(WorkOrderFixtures.newOrder(site).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.NEW);
            assertThat(WorkOrderFixtures.assigned(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.ASSIGNED);
            assertThat(WorkOrderFixtures.enRoute(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.EN_ROUTE);
            assertThat(WorkOrderFixtures.inProgress(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.IN_PROGRESS);
            assertThat(WorkOrderFixtures.onHold(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.ON_HOLD);
            assertThat(WorkOrderFixtures.completed(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.COMPLETED);
            assertThat(WorkOrderFixtures.closed(site, techId).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.CLOSED);
            assertThat(WorkOrderFixtures.cancelled(site).workOrder().getState())
                    .isEqualTo(WorkOrderStatus.CANCELLED);
        }
    }

    // ---- InventoryFixtures --------------------------------------------------

    @Nested
    @DisplayName("InventoryFixtures")
    class InventoryFixturesTests {

        @Test
        @DisplayName("nextPart produces a part with reserved PART- number prefix")
        void nextPartHasReservedPrefix() {
            Part p = InventoryFixtures.nextPart().build();
            assertThat(p.getPartNumber()).startsWith("PART-");
            assertThat(p.getId()).isNotNull();
        }

        @Test
        @DisplayName("zeroBalance produces StockBalance with quantityOnHand = 0")
        void zeroBalance() {
            UUID partId = DeterministicIds.next();
            UUID locId  = DeterministicIds.next();
            StockBalance sb = InventoryFixtures.zeroBalance(partId, locId).build();
            assertThat(sb.getQuantityOnHand()).isZero();
        }

        @Test
        @DisplayName("positiveBalance produces StockBalance with the requested quantity")
        void positiveBalance() {
            UUID partId = DeterministicIds.next();
            UUID locId  = DeterministicIds.next();
            StockBalance sb = InventoryFixtures.positiveBalance(partId, locId, 42).build();
            assertThat(sb.getQuantityOnHand()).isEqualTo(42);
        }

        @Test
        @DisplayName("zero and positive balances are distinct cases")
        void zeroAndPositiveAreDistinct() {
            UUID partId = DeterministicIds.next();
            UUID locId  = DeterministicIds.next();
            StockBalance zero = InventoryFixtures.zeroBalance(partId, locId).build();
            StockBalance pos  = InventoryFixtures.positiveBalance(partId, locId, 1).build();
            assertThat(zero.getQuantityOnHand()).isNotEqualTo(pos.getQuantityOnHand());
        }

        @Test
        @DisplayName("vehicleStock location has VEHICLE type and non-null technicianId")
        void vehicleLocationHasTechnicianId() {
            UUID techId = DeterministicIds.next();
            StockLocation loc = InventoryFixtures.vehicleStock("Van-001", techId).build();
            assertThat(loc.getLocationType()).isEqualTo(
                    com.fieldservice.inventory.domain.LocationType.VEHICLE);
            assertThat(loc.getTechnicianId()).isEqualTo(techId);
        }

        @Test
        @DisplayName("SLA policy builders produce correct priorities")
        void slaPolicies() {
            assertThat(InventoryFixtures.slaLow().build().getPriority()).isEqualTo("LOW");
            assertThat(InventoryFixtures.slaMedium().build().getPriority()).isEqualTo("MEDIUM");
            assertThat(InventoryFixtures.slaHigh().build().getPriority()).isEqualTo("HIGH");
            assertThat(InventoryFixtures.slaCritical().build().getPriority()).isEqualTo("CRITICAL");
        }
    }

    // ---- ScenarioFixtures ---------------------------------------------------

    @Nested
    @DisplayName("ScenarioFixtures")
    class ScenarioFixturesTests {

        @Test
        @DisplayName("dispatchReady scenario produces all expected aggregates")
        void dispatchReadyCompleteness() {
            ScenarioFixtures.DispatchReadyScenario s = ScenarioFixtures.dispatchReady();
            assertThat(s.customer()).isNotNull();
            assertThat(s.site()).isNotNull();
            assertThat(s.technicians()).hasSize(3);
            assertThat(s.certifications()).hasSize(3);
            assertThat(s.workOrder().getState()).isEqualTo(WorkOrderStatus.NEW);
            assertThat(s.stockBalance().getQuantityOnHand()).isGreaterThan(0);
        }

        @Test
        @DisplayName("dispatchReady technicians have mixed certification states")
        void dispatchReadyMixedCertifications() {
            ScenarioFixtures.DispatchReadyScenario s = ScenarioFixtures.dispatchReady();
            Instant fixed = DeterministicIds.FIXED_INSTANT;
            // tech[0]: valid
            TechnicianCertification cert0 = s.certifications().get(0);
            assertThat(cert0.getExpiresAt()).isAfter(fixed);
            // tech[1]: expired
            TechnicianCertification cert1 = s.certifications().get(1);
            assertThat(cert1.getExpiresAt()).isBefore(fixed);
            // tech[2]: non-expiring
            TechnicianCertification cert2 = s.certifications().get(2);
            assertThat(cert2.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("fullLifecycle scenario has exactly eight work orders")
        void fullLifecycleHasEightOrders() {
            ScenarioFixtures.FullLifecycleScenario s = ScenarioFixtures.fullLifecycle();
            assertThat(s.workOrders()).hasSize(8);
        }

        @Test
        @DisplayName("determinism: two builds of dispatchReady produce identical IDs")
        void determinism() {
            DeterministicIds.reset();
            ScenarioFixtures.DispatchReadyScenario first = ScenarioFixtures.dispatchReady();
            DeterministicIds.reset();
            ScenarioFixtures.DispatchReadyScenario second = ScenarioFixtures.dispatchReady();

            assertThat(first.customer().getId()).isEqualTo(second.customer().getId());
            assertThat(first.site().getId()).isEqualTo(second.site().getId());
            assertThat(first.workOrder().getId()).isEqualTo(second.workOrder().getId());
            for (int i = 0; i < 3; i++) {
                assertThat(first.technicians().get(i).getId())
                        .isEqualTo(second.technicians().get(i).getId());
            }
        }
    }
}
