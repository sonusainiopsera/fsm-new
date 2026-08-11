package com.fieldservice.workorder;

import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import jakarta.persistence.EntityManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Reusable test fixture builder that produces a {@link WorkOrder} in any of the eight
 * lifecycle states by creating a NEW work order and walking it through the transition table.
 *
 * <p>Uses a fixed DISPATCHER principal for all transitioning operations. The caller must
 * provide an active Testcontainers transaction environment (EntityManager + TransactionTemplate
 * + WorkOrderTransitionService).
 *
 * <p>Fixed site / customer IDs from V100 fixtures:
 * <ul>
 *   <li>Customer A: {@code 00000000-0000-0000-0000-000000000001}</li>
 *   <li>Site A1:    {@code 10000000-0000-0000-0000-000000000001}</li>
 * </ul>
 */
public class WorkOrderFixtureBuilder {

    public static final UUID FIXTURE_CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID FIXTURE_SITE_ID     = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final UUID FIXTURE_DISPATCHER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final EntityManager entityManager;
    private final TransactionTemplate txTemplate;
    private final WorkOrderTransitionService transitionService;

    public WorkOrderFixtureBuilder(
            EntityManager entityManager,
            TransactionTemplate txTemplate,
            WorkOrderTransitionService transitionService) {
        this.entityManager = entityManager;
        this.txTemplate = txTemplate;
        this.transitionService = transitionService;
    }

    /**
     * Create a work order in the given target state.
     *
     * @param targetState desired final lifecycle state
     * @return the ID of the created (and possibly transitioned) work order
     */
    public UUID createInState(WorkOrderState targetState) {
        UUID[] id = {null};
        txTemplate.executeWithoutResult(status -> {
            id[0] = persistNewWorkOrder("Fixture WO [" + targetState + "] " + UUID.randomUUID());
        });

        withDispatcherPrincipal(() -> {
            switch (targetState) {
                case NEW -> { /* already there */ }
                case ASSIGNED    -> applyInTx(id[0], WorkOrderEvent.ASSIGN);
                case EN_ROUTE    -> { applyInTx(id[0], WorkOrderEvent.ASSIGN);
                                      applyInTx(id[0], WorkOrderEvent.DEPART); }
                case IN_PROGRESS -> { applyInTx(id[0], WorkOrderEvent.ASSIGN);
                                      applyInTx(id[0], WorkOrderEvent.START); }
                case ON_HOLD     -> { applyInTx(id[0], WorkOrderEvent.ASSIGN);
                                      applyInTx(id[0], WorkOrderEvent.START);
                                      applyInTx(id[0], WorkOrderEvent.HOLD); }
                case COMPLETED   -> { applyInTx(id[0], WorkOrderEvent.ASSIGN);
                                      applyInTx(id[0], WorkOrderEvent.START);
                                      applyInTx(id[0], WorkOrderEvent.COMPLETE); }
                case CLOSED      -> { applyInTx(id[0], WorkOrderEvent.ASSIGN);
                                      applyInTx(id[0], WorkOrderEvent.START);
                                      applyInTx(id[0], WorkOrderEvent.COMPLETE);
                                      applyInTx(id[0], WorkOrderEvent.CLOSE); }
                case CANCELLED   -> applyInTx(id[0], WorkOrderEvent.CANCEL);
            }
        });

        return id[0];
    }

    private UUID persistNewWorkOrder(String title) {
        Site site = entityManager.getReference(Site.class, FIXTURE_SITE_ID);
        Customer customer = entityManager.getReference(Customer.class, FIXTURE_CUSTOMER_ID);
        WorkOrder wo = new WorkOrder();
        wo.setSite(site);
        wo.setCustomer(customer);
        wo.setState(WorkOrderState.NEW);
        wo.setPriority(WorkOrderPriority.MEDIUM);
        wo.setTitle(title);
        entityManager.persist(wo);
        entityManager.flush();
        return wo.getId();
    }

    private void applyInTx(UUID workOrderId, WorkOrderEvent event) {
        txTemplate.executeWithoutResult(status ->
                transitionService.applyEvent(workOrderId, event));
    }

    private void withDispatcherPrincipal(Runnable action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        FIXTURE_DISPATCHER_ID.toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"))));
        try {
            action.run();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
