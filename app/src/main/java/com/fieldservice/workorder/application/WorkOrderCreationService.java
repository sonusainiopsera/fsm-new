package com.fieldservice.workorder.application;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.site.domain.Site;
import com.fieldservice.site.repository.SiteRepository;
import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlineResult;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.web.WorkOrderCreationRequest;
import com.fieldservice.workorder.web.WorkOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles work order creation: persists the domain row, stamps SLA deadlines,
 * and publishes the outbox event — all within a single transaction.
 */
@Service
public class WorkOrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCreationService.class);

    private final WorkOrderRepository      workOrderRepository;
    private final ScopedQueryExecutor      scopedQueryExecutor;
    private final SiteRepository           siteRepository;
    private final RequestScopedAccessScope accessScope;
    private final SlaDeadlineCalculator    slaDeadlineCalculator;
    private final DomainEventPublisher     eventPublisher;

    public WorkOrderCreationService(WorkOrderRepository workOrderRepository,
                                    ScopedQueryExecutor scopedQueryExecutor,
                                    SiteRepository siteRepository,
                                    RequestScopedAccessScope accessScope,
                                    SlaDeadlineCalculator slaDeadlineCalculator,
                                    DomainEventPublisher eventPublisher) {
        this.workOrderRepository   = workOrderRepository;
        this.scopedQueryExecutor   = scopedQueryExecutor;
        this.siteRepository        = siteRepository;
        this.accessScope           = accessScope;
        this.slaDeadlineCalculator = slaDeadlineCalculator;
        this.eventPublisher        = eventPublisher;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER')")
    @Transactional
    public WorkOrderResponse create(WorkOrderCreationRequest request) {
        AccessScope scope  = accessScope.get();
        Instant     now    = Instant.now();

        // Resolve site through scope predicate (403 if out of scope)
        Site site = scopedQueryExecutor
                .findById(siteRepository, request.siteId(), scope, Site.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "site", "Site not found or outside caller scope"));

        // Stamp SLA deadlines in same transaction — throws SlaPolicyUnavailableException (422) if no policy
        SlaDeadlineResult sla = slaDeadlineCalculator.calculate(request.priority(), now);

        WorkOrderStatus initialState = request.assignedTechnicianId() != null
                ? WorkOrderStatus.ASSIGNED : WorkOrderStatus.NEW;

        WorkOrder workOrder = new WorkOrder(
                request.reference(),
                initialState,
                request.priority(),
                site,
                request.assignedTechnicianId());

        if (request.description() != null) {
            workOrder.setDescription(request.description());
        }
        workOrder.applyDeadlines(sla.responseDueAt(), sla.resolutionDueAt(), sla.atRiskAt());

        workOrderRepository.save(workOrder);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "WORK_ORDER_CREATED",
                "WORK_ORDER",
                workOrder.getId(),
                now,
                MDC.get("traceId"),
                scope.userId(),
                new WorkOrderCreatedPayload(
                        workOrder.getId(),
                        workOrder.getReference(),
                        workOrder.getPriority(),
                        site.getId(),
                        request.assignedTechnicianId(),
                        sla.responseDueAt(),
                        sla.resolutionDueAt(),
                        sla.atRiskAt())));

        log.info("work_order_created id={} reference={} priority={} site_id={} actor={}",
                workOrder.getId(), workOrder.getReference(), workOrder.getPriority(),
                site.getId(), scope.userId());

        return WorkOrderResponse.from(workOrder);
    }
}
