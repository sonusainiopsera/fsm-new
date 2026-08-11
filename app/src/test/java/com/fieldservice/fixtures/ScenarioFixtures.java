package com.fieldservice.fixtures;

import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.site.domain.Site;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Named multi-aggregate scenarios composed from the individual fixture builders.
 *
 * <p>Call {@link DeterministicIds#reset()} before building a scenario to ensure
 * reproducible identifier sequences across test runs.
 *
 * <p>Available scenarios:
 * <ul>
 *   <li>{@link #dispatchReady()} — one NEW work order, three technicians of mixed
 *       certification currency, and a stocked warehouse, ready for dispatch logic.</li>
 *   <li>{@link #fullLifecycle()} — one work order per lifecycle state (8 total),
 *       sharing one customer, one site, and one technician.</li>
 * </ul>
 */
public final class ScenarioFixtures {

    private ScenarioFixtures() {}

    // ---- Dispatch-ready scenario --------------------------------------------

    /**
     * A dispatch-ready scenario: one NEW work order, three technicians with
     * different certification states, and a stocked warehouse.
     *
     * <p>Technician index:
     * <ul>
     *   <li>[0] — has a currently valid certification (expiring in 30 days)</li>
     *   <li>[1] — has an expired certification (expired yesterday)</li>
     *   <li>[2] — has a non-expiring certification (no expiry date)</li>
     * </ul>
     */
    public static DispatchReadyScenario dispatchReady() {
        DeterministicIds.reset();

        // Customer + site
        CustomerAccount customer = new CustomerAccount(DeterministicIds.next(), "Fixture Corp");
        Site site = new Site(DeterministicIds.next(), "Fixture Corp HQ", customer.getId());

        // Users + technicians
        AppUser user0 = UserFixtures.technician()
                .withEmail("tech-alpha@example.local").withDisplayName("Alpha Tech").build();
        AppUser user1 = UserFixtures.technician()
                .withEmail("tech-beta@example.local").withDisplayName("Beta Tech").build();
        AppUser user2 = UserFixtures.technician()
                .withEmail("tech-gamma@example.local").withDisplayName("Gamma Tech").build();

        RoleAssignment role0 = UserFixtures.roleFor(user0, AppRole.TECHNICIAN).build();
        RoleAssignment role1 = UserFixtures.roleFor(user1, AppRole.TECHNICIAN).build();
        RoleAssignment role2 = UserFixtures.roleFor(user2, AppRole.TECHNICIAN).build();

        Technician tech0 = TechnicianFixtures.named(user0.getId(), "Alpha Tech").build();
        Technician tech1 = TechnicianFixtures.named(user1.getId(), "Beta Tech").build();
        Technician tech2 = TechnicianFixtures.named(user2.getId(), "Gamma Tech").build();

        // Certifications with mixed currency
        TechnicianCertification cert0 = TechnicianFixtures.validCertification(tech0.getId()).build();
        TechnicianCertification cert1 = TechnicianFixtures.expiredCertification(tech1.getId()).build();
        TechnicianCertification cert2 = TechnicianFixtures.nonExpiringCertification(tech2.getId()).build();

        // Inventory
        Part part = InventoryFixtures.part("PART-DISP-001", "Dispatch Test Part").build();
        StockLocation warehouse = InventoryFixtures.warehouse("Central Warehouse").build();
        StockBalance stock = InventoryFixtures.positiveBalance(part.getId(), warehouse.getId(), 20).build();

        // Work order
        WorkOrderFixtures.WorkOrderGraph graph = WorkOrderFixtures.newOrder(site);

        return new DispatchReadyScenario(
                customer, site,
                List.of(user0, user1, user2),
                List.of(role0, role1, role2),
                List.of(tech0, tech1, tech2),
                List.of(cert0, cert1, cert2),
                graph.workOrder(), part, warehouse, stock
        );
    }

    public record DispatchReadyScenario(
            CustomerAccount customer,
            Site site,
            List<AppUser> users,
            List<RoleAssignment> roles,
            List<Technician> technicians,
            List<TechnicianCertification> certifications,
            WorkOrder workOrder,
            Part part,
            StockLocation warehouse,
            StockBalance stockBalance
    ) {}

    // ---- Full-lifecycle scenario ---------------------------------------------

    /**
     * One work order in each of the eight lifecycle states, sharing one customer,
     * one site, and one technician.  COMPLETED and CLOSED orders carry an
     * {@link Assignment} with a non-null {@code releasedAt} (logged labour time).
     */
    public static FullLifecycleScenario fullLifecycle() {
        DeterministicIds.reset();

        CustomerAccount customer = new CustomerAccount(DeterministicIds.next(), "Lifecycle Corp");
        Site site = new Site(DeterministicIds.next(), "Lifecycle Site", customer.getId());

        AppUser user = UserFixtures.technician().build();
        RoleAssignment role = UserFixtures.roleFor(user, AppRole.TECHNICIAN).build();
        Technician technician = TechnicianFixtures.defaults(user.getId()).build();

        UUID techId = technician.getId();

        List<WorkOrderFixtures.WorkOrderGraph> graphs = new ArrayList<>();
        graphs.add(WorkOrderFixtures.newOrder(site));
        graphs.add(WorkOrderFixtures.assigned(site, techId));
        graphs.add(WorkOrderFixtures.enRoute(site, techId));
        graphs.add(WorkOrderFixtures.inProgress(site, techId));
        graphs.add(WorkOrderFixtures.onHold(site, techId));
        graphs.add(WorkOrderFixtures.completed(site, techId));
        graphs.add(WorkOrderFixtures.closed(site, techId));
        graphs.add(WorkOrderFixtures.cancelled(site));

        return new FullLifecycleScenario(customer, site, user, role, technician, graphs);
    }

    public record FullLifecycleScenario(
            CustomerAccount customer,
            Site site,
            AppUser user,
            RoleAssignment role,
            Technician technician,
            List<WorkOrderFixtures.WorkOrderGraph> workOrders
    ) {}
}
