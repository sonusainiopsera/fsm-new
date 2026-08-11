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
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RoleAssignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade composing multi-aggregate named scenarios used across the integration suite.
 *
 * <p>All scenarios start from {@link DeterministicIds#resetSequence()} and are therefore
 * reproducible: calling {@code ScenarioFixtures.dispatchReady()} twice in the same JVM
 * (after resetting the sequence) produces byte-identical identifiers and deadlines.
 *
 * <p>This class builds pure object graphs only. Callers that need the graph in the
 * database must persist each element in dependency order using an {@code EntityManager}
 * or the appropriate repositories.
 */
public final class ScenarioFixtures {

    private ScenarioFixtures() {}

    // -----------------------------------------------------------------------
    // Dispatch-ready scenario
    // -----------------------------------------------------------------------

    /**
     * A fully wired dispatch-ready scenario: one NEW HIGH-priority work order at a
     * customer site, four technicians (two with current certs, one expiring-soon,
     * one with an expired cert), one warehouse location stocked with the required part,
     * and one zero-stock location to exercise the out-of-stock path.
     */
    public static DispatchReadyScenario dispatchReady() {
        DeterministicIds.resetSequence();

        // --- Customer and site ---
        Customer customer = CustomerFixtures.customer()
                .withName("Dispatch Corp")
                .withContactEmail("dispatch@example.com")
                .build();
        Site site = CustomerFixtures.site(customer)
                .withName("Dispatch Site Alpha")
                .build();
        Asset asset = CustomerFixtures.hvacAsset(site).build();

        // --- Work order ---
        WorkOrder workOrder = WorkOrderFixtures.newWorkOrder()
                .withCustomer(customer)
                .withSite(site)
                .withPriority(WorkOrderPriority.HIGH)
                .withTitle("HVAC emergency repair at Dispatch Site Alpha")
                .build()
                .workOrder();

        // --- Users and technicians ---
        AppUser dispatcherUser = UserFixtures.dispatcher()
                .withEmail("dispatcher.scenario@example.com")
                .build();
        RoleAssignment dispatcherRole = new RoleAssignment(
                dispatcherUser.getId(), IdentityRole.DISPATCHER, DeterministicIds.EPOCH, null);

        List<TechnicianProfile> technicians = buildTechnicianPool();

        // --- Inventory ---
        Part hvacPart = InventoryFixtures.activePart()
                .withPartNumber("PN-DS-001")
                .withSku("SKU-DS-001")
                .withName("Refrigerant R-410A 25lb")
                .withDescription("Refrigerant cylinder for dispatch scenario")
                .build();

        StockLocation warehouse = InventoryFixtures.warehouseLocation()
                .withName("Dispatch Warehouse")
                .build();

        // Zero-stock location to test out-of-stock path
        StockLocation emptyLocation = InventoryFixtures.warehouseLocation()
                .withName("Dispatch Empty Location")
                .build();

        return new DispatchReadyScenario(
                customer, site, asset, workOrder,
                dispatcherUser, dispatcherRole,
                technicians, hvacPart, warehouse, emptyLocation);
    }

    private static List<TechnicianProfile> buildTechnicianPool() {
        List<TechnicianProfile> pool = new ArrayList<>();

        // Tech 1: current HVAC cert
        UserFixtures.UserWithRole tech1UserRole = UserFixtures.technician()
                .withEmail("tech1.scenario@example.com")
                .withDisplayName("Scenario Tech 1 (Current)")
                .buildWithRole();
        TechnicianFixtures.TechnicianWithCerts tech1 =
                TechnicianFixtures.active()
                        .withEmployeeNo("DS-EMP-001")
                        .withPhone("+15555550011")
                        .build(tech1UserRole.user());
        pool.add(new TechnicianProfile(tech1UserRole.user(), tech1UserRole.roleAssignment(), tech1));

        // Tech 2: current HVAC cert (second eligible candidate)
        UserFixtures.UserWithRole tech2UserRole = UserFixtures.technician()
                .withEmail("tech2.scenario@example.com")
                .withDisplayName("Scenario Tech 2 (Current)")
                .buildWithRole();
        TechnicianFixtures.TechnicianWithCerts tech2 =
                TechnicianFixtures.active()
                        .withEmployeeNo("DS-EMP-002")
                        .withPhone("+15555550012")
                        .build(tech2UserRole.user());
        pool.add(new TechnicianProfile(tech2UserRole.user(), tech2UserRole.roleAssignment(), tech2));

        // Tech 3: expiring-soon cert (eligible but flagged)
        UserFixtures.UserWithRole tech3UserRole = UserFixtures.technician()
                .withEmail("tech3.scenario@example.com")
                .withDisplayName("Scenario Tech 3 (Expiring Soon)")
                .buildWithRole();
        TechnicianFixtures.TechnicianWithCerts tech3 =
                TechnicianFixtures.expiringSoon()
                        .withEmployeeNo("DS-EMP-003")
                        .withPhone("+15555550013")
                        .build(tech3UserRole.user());
        pool.add(new TechnicianProfile(tech3UserRole.user(), tech3UserRole.roleAssignment(), tech3));

        // Tech 4: expired cert (ineligible)
        UserFixtures.UserWithRole tech4UserRole = UserFixtures.technician()
                .withEmail("tech4.scenario@example.com")
                .withDisplayName("Scenario Tech 4 (Expired)")
                .buildWithRole();
        TechnicianFixtures.TechnicianWithCerts tech4 =
                TechnicianFixtures.expiredCertification()
                        .withEmployeeNo("DS-EMP-004")
                        .withPhone("+15555550014")
                        .build(tech4UserRole.user());
        pool.add(new TechnicianProfile(tech4UserRole.user(), tech4UserRole.roleAssignment(), tech4));

        return List.copyOf(pool);
    }

    // -----------------------------------------------------------------------
    // Full-state work order scenario
    // -----------------------------------------------------------------------

    /**
     * Eight work orders — one in each lifecycle state — all sharing the same customer/site.
     * Useful for state-transition and audit tests.
     */
    public static AllStatesScenario allWorkOrderStates() {
        DeterministicIds.resetSequence();

        Customer customer = CustomerFixtures.customer().build();
        Site site = CustomerFixtures.site(customer).build();

        List<WorkOrderFixtures.WorkOrderResult> results = new ArrayList<>();
        for (WorkOrderState state : WorkOrderState.values()) {
            WorkOrderFixtures.WorkOrderResult r = WorkOrderFixtures.inState(state)
                    .withCustomer(customer)
                    .withSite(site)
                    .withTitle("All-states fixture [" + state + "]")
                    .build();
            results.add(r);
        }

        return new AllStatesScenario(customer, site, List.copyOf(results));
    }

    // -----------------------------------------------------------------------
    // Value carriers
    // -----------------------------------------------------------------------

    public record TechnicianProfile(
            AppUser user,
            RoleAssignment roleAssignment,
            TechnicianFixtures.TechnicianWithCerts technicianWithCerts) {

        public Technician technician() { return technicianWithCerts.technician(); }
        public List<TechnicianCertification> certifications() { return technicianWithCerts.certifications(); }
    }

    public record DispatchReadyScenario(
            Customer customer,
            Site site,
            Asset asset,
            WorkOrder workOrder,
            AppUser dispatcherUser,
            RoleAssignment dispatcherRole,
            List<TechnicianProfile> technicianPool,
            Part hvacPart,
            StockLocation warehouse,
            StockLocation emptyLocation) {

        /** Returns a stocked balance for the HVAC part at the warehouse (created after persist). */
        public StockBalance stockedBalance(java.util.UUID partId, java.util.UUID locationId) {
            return InventoryFixtures.positiveBalance(partId, locationId, 10);
        }

        /** Returns a zero-stock balance for the HVAC part at the empty location. */
        public StockBalance zeroBalance(java.util.UUID partId, java.util.UUID locationId) {
            return InventoryFixtures.zeroBalance(partId, locationId);
        }

        public List<AppUser> allUsers() {
            List<AppUser> users = new ArrayList<>();
            users.add(dispatcherUser);
            technicianPool.forEach(tp -> users.add(tp.user()));
            return List.copyOf(users);
        }
    }

    public record AllStatesScenario(
            Customer customer,
            Site site,
            List<WorkOrderFixtures.WorkOrderResult> workOrderResults) {

        public List<WorkOrder> workOrders() {
            return workOrderResults.stream().map(WorkOrderFixtures.WorkOrderResult::workOrder).toList();
        }

        public List<Assignment> assignments() {
            return workOrderResults.stream()
                    .map(WorkOrderFixtures.WorkOrderResult::assignment)
                    .filter(a -> a != null)
                    .toList();
        }
    }
}
