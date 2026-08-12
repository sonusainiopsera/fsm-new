package com.fieldservice.workorder.application;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.customer.CustomerRepository;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.site.SiteRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderOrigin;
import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.outbox.payload.WorkOrderCreatedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.sla.SlaDeadlineCalculator;
import com.fieldservice.sla.SlaDeadlines;
import com.fieldservice.sla.SlaPolicy;
import com.fieldservice.sla.SlaPolicyProvider;
import com.fieldservice.workorder.api.dto.CreateWorkOrderRequest;
import com.fieldservice.workorder.duplicates.DuplicateCandidate;
import com.fieldservice.workorder.duplicates.DuplicateDetectionService;
import com.fieldservice.workorder.duplicates.FaultSignatureNormalizer;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
 * database write occurs (fail-closed, AC-3).
 *
 * <p>CUSTOMER principals may only create work orders for sites that belong to their
 * account and may not exceed MEDIUM priority (AC-7).
 */
@Service
@Transactional
public class WorkOrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCreateService.class);

    /** Portal-submitted requests may not exceed this priority. */
    private static final WorkOrderPriority CUSTOMER_PRIORITY_CEILING = WorkOrderPriority.MEDIUM;

    private final WorkOrderRepository workOrderRepository;
    private final CustomerRepository customerRepository;
    private final SiteRepository siteRepository;
    private final AssetRepository assetRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final SlaDeadlineCalculator slaDeadlineCalculator;
    private final SlaPolicyProvider slaPolicyProvider;
    private final DomainEventPublisher eventPublisher;
    private final AccessScopeResolver scopeResolver;
    private final EntityManager entityManager;
    private final FaultSignatureNormalizer signatureNormalizer;
    private final DuplicateDetectionService duplicateDetectionService;

    public WorkOrderCreateService(
            WorkOrderRepository workOrderRepository,
            CustomerRepository customerRepository,
            SiteRepository siteRepository,
            AssetRepository assetRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            SlaDeadlineCalculator slaDeadlineCalculator,
            SlaPolicyProvider slaPolicyProvider,
            DomainEventPublisher eventPublisher,
            AccessScopeResolver scopeResolver,
            EntityManager entityManager,
            FaultSignatureNormalizer signatureNormalizer,
            DuplicateDetectionService duplicateDetectionService) {
        this.workOrderRepository = workOrderRepository;
        this.customerRepository  = customerRepository;
        this.siteRepository      = siteRepository;
        this.assetRepository     = assetRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.slaDeadlineCalculator = slaDeadlineCalculator;
        this.slaPolicyProvider   = slaPolicyProvider;
        this.eventPublisher      = eventPublisher;
        this.scopeResolver       = scopeResolver;
        this.entityManager       = entityManager;
        this.signatureNormalizer  = signatureNormalizer;
        this.duplicateDetectionService = duplicateDetectionService;
    }

    /** Result of a work order creation, including advisory duplicate candidates. */
    public record CreateResult(WorkOrder workOrder, List<DuplicateCandidate> duplicateCandidates) {}

    /**
     * Creates a new work order and stamps SLA deadlines.
     *
     * @throws com.fieldservice.sla.SlaPolicyUnavailableException if no active policy for priority
     * @throws NotFoundException if customer or site does not exist
     * @throws SiteCustomerMismatchException if site does not belong to customer (AC-6)
     * @throws AssetSiteMismatchException if asset is not at the site (AC-6)
     * @throws ScopedAccessDeniedException if CUSTOMER tries to create outside their account (AC-7)
     */
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    public CreateResult create(CreateWorkOrderRequest req) {
        Instant now = Instant.now();

        AccessScope scope = scopeResolver.resolve();

        WorkOrderPriority priority = WorkOrderPriority.valueOf(req.priority());

        // CUSTOMER portal: enforce account ownership and priority ceiling (AC-7)
        if (scope.isCustomer()) {
            validateCustomerScope(scope, req.customerId());
            if (priority.ordinal() > CUSTOMER_PRIORITY_CEILING.ordinal()) {
                throw new ScopedAccessDeniedException(
                        "CUSTOMER portal submissions may not exceed priority " +
                        CUSTOMER_PRIORITY_CEILING.name());
            }
        }

        // Resolve SLA policy BEFORE writing any row — fail-closed on missing policy (AC-3)
        SlaPolicy policy   = slaPolicyProvider.resolveActivePolicy(req.priority(), now);
        SlaDeadlines deadlines = slaDeadlineCalculator.calculate(req.priority(), now);

        Customer customer = scopedQueryExecutor.findById(Customer.class, req.customerId(), customerRepository);
        Site site         = scopedQueryExecutor.findById(Site.class, req.siteId(), siteRepository);

        // Referential integrity: site must belong to the customer (AC-6)
        if (!req.customerId().equals(site.getCustomerId())) {
            throw new SiteCustomerMismatchException(req.siteId(), req.customerId());
        }

        WorkOrder wo = new WorkOrder();
        wo.setCustomer(customer);
        wo.setSite(site);
        wo.setPriority(priority);
        wo.setState(WorkOrderState.NEW);
        wo.setTitle(req.title());
        wo.setFaultDescription(req.faultDescription());
        wo.setDescription(req.faultDescription()); // backward-compat mirror
        wo.setFaultSignature(signatureNormalizer.normalize(req.faultDescription()));
        wo.setResponseDueAt(deadlines.responseDueAt());
        wo.setResolutionDueAt(deadlines.resolutionDueAt());
        wo.setAtRiskAt(deadlines.atRiskAt());
        wo.setSlaDeadline(deadlines.resolutionDueAt());
        wo.setAppliedSlaPolicyId(policy.id());

        // Optional asset validation (AC-6)
        if (req.assetId() != null) {
            Asset asset = scopedQueryExecutor.findById(Asset.class, req.assetId(), assetRepository);
            if (!req.siteId().equals(asset.getSiteId())) {
                throw new AssetSiteMismatchException(req.assetId(), req.siteId());
            }
            wo.setAssetId(req.assetId());
        }

        // Persist work order first so the ID is available for reference generation
        WorkOrder saved = workOrderRepository.save(wo);
        entityManager.flush(); // needed to obtain the generated ID before reference seq call

        // Generate human-readable reference using the database sequence
        String ref = generateReference();
        saved.setReference(ref);
        saved = workOrderRepository.save(saved);

        publishCreatedEvent(saved, policy, now);

        // Advisory duplicate detection — never blocks creation; degrades to empty list on error
        List<DuplicateCandidate> candidates = List.of();
        try {
            candidates = duplicateDetectionService.detect(saved);
        } catch (Exception ex) {
            log.warn("duplicate.detection.degraded: workOrderId={}, error={}", saved.getId(), ex.getMessage());
        }

        return new CreateResult(saved, candidates);
    }

    /**
     * Creates a portal-originated work order with origin=PORTAL.
     *
     * <p>This overload is intended exclusively for the portal submission path.
     * Ownership validation (site belongs to the customer account) is performed
     * by {@code PortalServiceRequestService} via {@code CustomerAccessScope} before
     * calling this method — the pre-validated {@code customerId} is trusted here.
     *
     * <p>Priority ceiling (MEDIUM) is enforced here in addition to portal-side validation
     * so no other caller can escalate a portal submission.
     *
     * @param req        creation request with pre-validated customerId and portal-capped priority
     * @param customerId the caller's customer account UUID (resolved via CustomerAccessScope)
     * @return the persisted {@link WorkOrder} with origin=PORTAL and stamped SLA deadlines
     * @throws com.fieldservice.sla.SlaPolicyUnavailableException if no active policy for priority
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public CreateResult createForPortal(CreateWorkOrderRequest req, UUID customerId) {
        Instant now = Instant.now();

        WorkOrderPriority priority = WorkOrderPriority.valueOf(req.priority());
        if (priority.ordinal() > CUSTOMER_PRIORITY_CEILING.ordinal()) {
            throw new ScopedAccessDeniedException(
                    "Portal submissions may not exceed priority " + CUSTOMER_PRIORITY_CEILING.name());
        }

        SlaPolicy policy     = slaPolicyProvider.resolveActivePolicy(req.priority(), now);
        SlaDeadlines deadlines = slaDeadlineCalculator.calculate(req.priority(), now);

        Customer customer = scopedQueryExecutor.findById(Customer.class, customerId, customerRepository);
        Site site         = scopedQueryExecutor.findById(Site.class, req.siteId(), siteRepository);

        if (!customerId.equals(site.getCustomerId())) {
            throw new SiteCustomerMismatchException(req.siteId(), customerId);
        }

        WorkOrder wo = new WorkOrder();
        wo.setCustomer(customer);
        wo.setSite(site);
        wo.setPriority(priority);
        wo.setState(WorkOrderState.NEW);
        wo.setTitle(req.title());
        wo.setFaultDescription(req.faultDescription());
        wo.setDescription(req.faultDescription());
        wo.setFaultSignature(signatureNormalizer.normalize(req.faultDescription()));
        wo.setResponseDueAt(deadlines.responseDueAt());
        wo.setResolutionDueAt(deadlines.resolutionDueAt());
        wo.setAtRiskAt(deadlines.atRiskAt());
        wo.setSlaDeadline(deadlines.resolutionDueAt());
        wo.setAppliedSlaPolicyId(policy.id());
        wo.setOrigin(WorkOrderOrigin.PORTAL);

        if (req.assetId() != null) {
            Asset asset = scopedQueryExecutor.findById(Asset.class, req.assetId(), assetRepository);
            if (!req.siteId().equals(asset.getSiteId())) {
                throw new AssetSiteMismatchException(req.assetId(), req.siteId());
            }
            wo.setAssetId(req.assetId());
        }

        WorkOrder saved = workOrderRepository.save(wo);
        entityManager.flush();

        String ref = generateReference();
        saved.setReference(ref);
        saved = workOrderRepository.save(saved);

        publishCreatedEvent(saved, policy, now);

        List<DuplicateCandidate> candidates = List.of();
        try {
            candidates = duplicateDetectionService.detect(saved);
        } catch (Exception ex) {
            log.warn("duplicate.detection.degraded: workOrderId={}, error={}", saved.getId(), ex.getMessage());
        }

        return new CreateResult(saved, candidates);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void validateCustomerScope(AccessScope scope, UUID requestedCustomerId) {
        boolean ownAccount = scope.customerAccountIds() != null &&
                scope.customerAccountIds().contains(requestedCustomerId);
        if (!ownAccount) {
            throw new ScopedAccessDeniedException(
                    "CUSTOMER principal may only create work orders for their own account");
        }
    }

    private String generateReference() {
        Long seq = (Long) entityManager
                .createNativeQuery("SELECT nextval('work_order_reference_seq')")
                .getSingleResult();
        return "WO-%08d".formatted(seq);
    }

    private void publishCreatedEvent(WorkOrder wo, SlaPolicy policy, Instant now) {
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
