package com.fieldservice.workorder.application;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.asset.repository.AssetRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.site.domain.Site;
import com.fieldservice.site.repository.SiteRepository;
import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlineResult;
import com.fieldservice.sla.SlaPolicyProvider;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.workorder.WorkOrderErrorCodes;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderPriority;
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
import java.util.UUID;

/**
 * Handles work order creation: validates referential integrity, resolves the active SLA
 * policy, derives and persists deadlines, and publishes the outbox event — all within a
 * single transaction.
 *
 * <p>Security: method-level @PreAuthorize; CUSTOMER principals are additionally checked
 * for portal priority ceiling and site ownership via the scope predicate.
 */
@Service
public class WorkOrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCreationService.class);

    /** CUSTOMER portal submissions may not set priority above this ceiling. */
    static final WorkOrderPriority PORTAL_PRIORITY_CEILING = WorkOrderPriority.HIGH;

    private final WorkOrderRepository    workOrderRepository;
    private final ScopedQueryExecutor    scopedQueryExecutor;
    private final SiteRepository         siteRepository;
    private final AssetRepository        assetRepository;
    private final RequestScopedAccessScope accessScope;
    private final SlaDeadlineCalculator  slaDeadlineCalculator;
    private final SlaPolicyProvider      slaPolicyProvider;
    private final DomainEventPublisher   eventPublisher;

    public WorkOrderCreationService(WorkOrderRepository workOrderRepository,
                                    ScopedQueryExecutor scopedQueryExecutor,
                                    SiteRepository siteRepository,
                                    AssetRepository assetRepository,
                                    RequestScopedAccessScope accessScope,
                                    SlaDeadlineCalculator slaDeadlineCalculator,
                                    SlaPolicyProvider slaPolicyProvider,
                                    DomainEventPublisher eventPublisher) {
        this.workOrderRepository   = workOrderRepository;
        this.scopedQueryExecutor   = scopedQueryExecutor;
        this.siteRepository        = siteRepository;
        this.assetRepository       = assetRepository;
        this.accessScope           = accessScope;
        this.slaDeadlineCalculator = slaDeadlineCalculator;
        this.slaPolicyProvider     = slaPolicyProvider;
        this.eventPublisher        = eventPublisher;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER', 'CUSTOMER')")
    @Transactional
    public WorkOrderResponse create(WorkOrderCreationRequest request) {
        AccessScope scope  = accessScope.get();
        Instant     now    = Instant.now();

        // CUSTOMER portal priority ceiling
        if (scope.roles().contains("CUSTOMER")
                && request.priority().ordinal() > PORTAL_PRIORITY_CEILING.ordinal()) {
            throw new BusinessGuardException(
                    WorkOrderErrorCodes.PORTAL_PRIORITY_DENIED,
                    "CUSTOMER portal submissions may not exceed priority " + PORTAL_PRIORITY_CEILING.name());
        }

        // Resolve site through scope predicate (403 if out of scope or not found)
        Site site = scopedQueryExecutor
                .findById(siteRepository, request.siteId(), scope, Site.class)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "site", "Site not found or outside caller scope"));

        // Validate site belongs to the requested customer (AC6)
        if (!site.getCustomerId().equals(request.customerId())) {
            throw new WorkOrderReferentialException(
                    WorkOrderErrorCodes.SITE_CUSTOMER_MISMATCH, "siteId",
                    "Site " + request.siteId() + " does not belong to customer " + request.customerId());
        }

        // Validate asset belongs to the requested site (AC6)
        if (request.assetId() != null) {
            Asset asset = scopedQueryExecutor
                    .findById(assetRepository, request.assetId(), scope, Asset.class)
                    .orElseThrow(() -> new WorkOrderReferentialException(
                            WorkOrderErrorCodes.ASSET_SITE_MISMATCH, "assetId",
                            "Asset " + request.assetId() + " not found or outside caller scope"));
            if (!asset.getSiteId().equals(request.siteId())) {
                throw new WorkOrderReferentialException(
                        WorkOrderErrorCodes.ASSET_SITE_MISMATCH, "assetId",
                        "Asset " + request.assetId() + " is not located at site " + request.siteId());
            }
        }

        // Resolve SLA policy — throws SlaPolicyUnavailableException (→ 422) if none active
        SlaPolicy policy = slaPolicyProvider.resolve(request.priority().toDbValue(), now)
                .orElseThrow(() -> {
                    log.error("sla_policy_unavailable priority={}", request.priority());
                    return new com.fieldservice.sla.SlaPolicyUnavailableException(request.priority().toDbValue());
                });

        // Compute deadlines using injected calculator (respects clock-pause adjustments at creation = 0)
        SlaDeadlineResult sla = slaDeadlineCalculator.calculate(request.priority().toDbValue(), now);

        // Auto-generate human-readable reference from database sequence
        long seq      = workOrderRepository.nextRefSequence();
        String reference = String.format("WO-%06d", seq);

        WorkOrder workOrder = new WorkOrder(
                reference,
                WorkOrderStatus.NEW,
                request.priority().toDbValue(),
                site,
                null);

        workOrder.setDescription(request.faultDescription());
        workOrder.applyDeadlines(sla.responseDueAt(), sla.resolutionDueAt(), sla.atRiskAt(),
                policy.getId());

        if (request.assetId() != null) {
            workOrder.setAssetId(request.assetId());
        }

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
                        null,
                        sla.responseDueAt(),
                        sla.resolutionDueAt(),
                        sla.atRiskAt())));

        log.info("work_order_created id={} reference={} priority={} site_id={} customer_id={} actor={}",
                workOrder.getId(), workOrder.getReference(), workOrder.getPriority(),
                site.getId(), request.customerId(), scope.userId());

        return WorkOrderResponse.from(workOrder);
    }

    /**
     * Portal-specific creation path. Site ownership is validated against {@code accountId}
     * (resolved by the caller from {@link com.fieldservice.portal.access.CustomerAccessScope}).
     * Origin is stamped as PORTAL and priority defaults to the configured portal ceiling.
     *
     * @param accountId       caller's customer account id (from portal scope)
     * @param siteId          site to raise the request against
     * @param assetId         optional asset at the site
     * @param faultDescription description of the fault (PII — never logged at INFO+)
     * @param actorUserId     authenticated user id for the outbox event
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public WorkOrderResponse createFromPortal(UUID accountId, UUID siteId, UUID assetId,
                                               String faultDescription, UUID actorUserId) {
        Instant now = Instant.now();

        // Resolve site through a predicate combining id AND customer ownership.
        // This follows the "query through scope predicate" pattern rather than fetch-then-compare.
        Site site = siteRepository
                .findOne((root, q, cb) -> cb.and(
                        cb.equal(root.get("id"), siteId),
                        cb.equal(root.get("customerId"), accountId)))
                .orElseThrow(() -> new ScopedAccessDeniedException("site",
                        "Site not found or not accessible"));

        // Validate asset ownership via predicate combining id AND site id
        if (assetId != null) {
            assetRepository
                    .findOne((root, q, cb) -> cb.and(
                            cb.equal(root.get("id"), assetId),
                            cb.equal(root.get("siteId"), siteId)))
                    .orElseThrow(() -> new WorkOrderReferentialException(
                            WorkOrderErrorCodes.ASSET_SITE_MISMATCH, "assetId",
                            "Asset not found or not located at the supplied site"));
        }

        // Portal submissions use the NORMAL priority ceiling
        WorkOrderPriority priority = PORTAL_PRIORITY_CEILING;

        // SLA policy — throws SlaPolicyUnavailableException (→ 503) if none active
        slaPolicyProvider.resolve(priority.toDbValue(), now)
                .orElseThrow(() -> {
                    log.error("sla_policy_unavailable priority={} origin=PORTAL", priority);
                    return new com.fieldservice.sla.SlaPolicyUnavailableException(priority.toDbValue());
                });

        SlaDeadlineResult sla = slaDeadlineCalculator.calculate(priority.toDbValue(), now);

        long   seq       = workOrderRepository.nextRefSequence();
        String reference = String.format("WO-%06d", seq);

        WorkOrder workOrder = new WorkOrder(reference, WorkOrderStatus.NEW,
                priority.toDbValue(), site, null);
        workOrder.setDescription(faultDescription);
        workOrder.setOrigin("PORTAL");
        workOrder.applyDeadlines(sla.responseDueAt(), sla.resolutionDueAt(), sla.atRiskAt());

        if (assetId != null) {
            workOrder.setAssetId(assetId);
        }

        workOrderRepository.save(workOrder);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "WORK_ORDER_CREATED",
                "WORK_ORDER",
                workOrder.getId(),
                now,
                MDC.get("traceId"),
                actorUserId,
                new WorkOrderCreatedPayload(
                        workOrder.getId(),
                        workOrder.getReference(),
                        workOrder.getPriority(),
                        site.getId(),
                        "PORTAL",
                        sla.responseDueAt(),
                        sla.resolutionDueAt(),
                        sla.atRiskAt())));

        log.info("portal_work_order_created id={} reference={} site_id={} account_id={} actor={}",
                workOrder.getId(), workOrder.getReference(), site.getId(), accountId, actorUserId);

        return WorkOrderResponse.from(workOrder);
    }
}
