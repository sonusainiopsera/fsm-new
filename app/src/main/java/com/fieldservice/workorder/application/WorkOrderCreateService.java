package com.fieldservice.workorder.application;

import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.customer.CustomerRepository;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.site.SiteRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.outbox.payload.WorkOrderCreatedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlines;
import com.fieldservice.workorder.api.dto.CreateWorkOrderRequest;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Creates work orders with atomically stamped SLA deadlines.
 *
 * <p>All of the following are committed in the same database transaction:
 * <ol>
 *   <li>The {@link WorkOrder} domain row with deadlines stamped from the active policy.</li>
 *   <li>The Hibernate Envers revision (automatic, wired to the same transaction).</li>
 *   <li>The {@code WorkOrderCreated} outbox event via {@link DomainEventPublisher}.</li>
 * </ol>
 *
 * <p>If no active SLA policy exists for the requested priority, a
 * {@link com.fieldservice.sla.SlaPolicyUnavailableException} is thrown before any
 * database write occurs (fail-closed, AC-5).
 */
@Service
@Transactional
public class WorkOrderCreateService {

    private final WorkOrderRepository workOrderRepository;
    private final CustomerRepository customerRepository;
    private final SiteRepository siteRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final SlaDeadlineCalculator slaDeadlineCalculator;
    private final DomainEventPublisher eventPublisher;
    private final AccessScopeResolver scopeResolver;

    public WorkOrderCreateService(
            WorkOrderRepository workOrderRepository,
            CustomerRepository customerRepository,
            SiteRepository siteRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            SlaDeadlineCalculator slaDeadlineCalculator,
            DomainEventPublisher eventPublisher,
            AccessScopeResolver scopeResolver) {
        this.workOrderRepository = workOrderRepository;
        this.customerRepository = customerRepository;
        this.siteRepository = siteRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.slaDeadlineCalculator = slaDeadlineCalculator;
        this.eventPublisher = eventPublisher;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Creates a new work order and stamps SLA deadlines.
     *
     * @throws com.fieldservice.sla.SlaPolicyUnavailableException if no active policy for priority
     * @throws NotFoundException if customer or site does not exist
     */
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'ADMIN', 'MANAGER')")
    public WorkOrder create(CreateWorkOrderRequest req) {
        Instant now = Instant.now();

        // Resolve SLA deadlines BEFORE writing any row — fail-closed on missing policy
        SlaDeadlines deadlines = slaDeadlineCalculator.calculate(req.priority(), now);

        Customer customer = scopedQueryExecutor.findById(Customer.class, req.customerId(), customerRepository);
        Site site = scopedQueryExecutor.findById(Site.class, req.siteId(), siteRepository);

        WorkOrder wo = new WorkOrder();
        wo.setCustomer(customer);
        wo.setSite(site);
        wo.setPriority(WorkOrderPriority.valueOf(req.priority()));
        wo.setState(WorkOrderState.NEW);
        wo.setTitle(req.title());
        wo.setDescription(req.description());
        wo.setResponseDueAt(deadlines.responseDueAt());
        wo.setResolutionDueAt(deadlines.resolutionDueAt());
        wo.setAtRiskAt(deadlines.atRiskAt());
        wo.setSlaDeadline(deadlines.resolutionDueAt());

        WorkOrder saved = workOrderRepository.save(wo);

        publishCreatedEvent(saved, now);

        return saved;
    }

    private void publishCreatedEvent(WorkOrder wo, Instant now) {
        UUID actor = resolveActorId();
        var payload = new WorkOrderCreatedPayload(
                wo.getId(),
                wo.getPriority().name(),
                wo.getCustomerId(),
                wo.getSiteId(),
                wo.getResponseDueAt(),
                wo.getResolutionDueAt(),
                wo.getAtRiskAt(),
                now);
        Map<String, Object> payloadMap = PiiRedactionUtility.toPayloadMap(payload);
        DomainEvent event = DomainEvent.of(
                WorkOrderCreatedPayload.EVENT_TYPE,
                WorkOrderCreatedPayload.AGGREGATE_TYPE,
                wo.getId(),
                now,
                MDC.get("traceId"),
                actor,
                payloadMap);
        eventPublisher.publish(event);
    }

    private UUID resolveActorId() {
        try {
            return scopeResolver.resolve().userId();
        } catch (Exception ex) {
            return null;
        }
    }
}
