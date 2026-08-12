package com.fieldservice.workorder.duplicates;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.workorder.WorkOrderErrorCodes;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderTransitionService;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Validates and executes the link-and-cancel workflow.
 *
 * <p>A link is refused when:
 * <ul>
 *   <li>Source and target are the same work order (DUPLICATE_SELF_LINK)</li>
 *   <li>Source is already linked (DUPLICATE_ALREADY_LINKED)</li>
 *   <li>Target is not open (DUPLICATE_TARGET_NOT_OPEN)</li>
 *   <li>Linking would form a cycle or already-linked chain (DUPLICATE_LINK_CYCLE)</li>
 *   <li>Target is outside the caller's row scope (403 non-disclosure)</li>
 * </ul>
 *
 * <p>On success: inserts the link, cancels the source via WorkOrderTransitionService
 * with the CANCEL event (reason DUPLICATE_REQUEST stored in the link row), and marks
 * the source {@code excluded_from_sla_compliance = true}. All within one transaction.
 */
@Service
public class DuplicateLinkService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateLinkService.class);

    private static final int CHAIN_DEPTH_CAP = 10;

    private final WorkOrderRepository            workOrderRepository;
    private final WorkOrderDuplicateLinkRepository linkRepository;
    private final WorkOrderTransitionService     transitionService;
    private final ScopedQueryExecutor            scopedQueryExecutor;
    private final RequestScopedAccessScope       accessScope;
    private final DomainEventPublisher           eventPublisher;

    public DuplicateLinkService(WorkOrderRepository workOrderRepository,
                                WorkOrderDuplicateLinkRepository linkRepository,
                                WorkOrderTransitionService transitionService,
                                ScopedQueryExecutor scopedQueryExecutor,
                                RequestScopedAccessScope accessScope,
                                DomainEventPublisher eventPublisher) {
        this.workOrderRepository = workOrderRepository;
        this.linkRepository      = linkRepository;
        this.transitionService   = transitionService;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.accessScope         = accessScope;
        this.eventPublisher      = eventPublisher;
    }

    /**
     * Creates a duplicate link from source to target and cancels the source.
     *
     * @param sourceId work order to be marked as duplicate and cancelled
     * @param targetId the surviving work order (must be open and in scope)
     * @param reason   dispatcher's stated reason (stored immutably)
     * @return result with source state, target id, and link timestamp
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER', 'MANAGER')")
    @Transactional
    public DuplicateLinkResult link(UUID sourceId, UUID targetId, String reason) {
        AccessScope scope = accessScope.get();
        Instant now = Instant.now();

        // Self-link guard
        if (sourceId.equals(targetId)) {
            throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_SELF_LINK,
                    "A work order cannot be linked to itself.");
        }

        // Resolve source — must be in scope
        WorkOrder source = scopedQueryExecutor
                .findById(workOrderRepository, sourceId, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException("workOrder",
                        "Source work order not found or outside caller scope."));

        // Source must not already be linked
        if (linkRepository.findBySourceWorkOrderId(sourceId).isPresent()) {
            throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_ALREADY_LINKED,
                    "This work order is already linked as a duplicate.");
        }

        // Source must be open (not yet cancelled/closed)
        if (!WorkOrderStatus.openStates().contains(source.getState())) {
            throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_TARGET_NOT_OPEN,
                    "The source work order is not in an open state.");
        }

        // Resolve target through scope (403 on out-of-scope — no existence disclosure)
        WorkOrder target = scopedQueryExecutor
                .findById(workOrderRepository, targetId, scope, WorkOrder.class)
                .orElseThrow(() -> new ScopedAccessDeniedException("workOrder",
                        "Target work order not found or outside caller scope."));

        // Target must be open
        if (!WorkOrderStatus.openStates().contains(target.getState())) {
            throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_TARGET_NOT_OPEN,
                    "The target work order must be in an open state.");
        }

        // Resolve the surviving root of the target chain (depth-capped)
        UUID survivingRoot = resolveRoot(targetId);

        // Cycle guard: root must not be the source
        if (survivingRoot.equals(sourceId)) {
            throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_LINK_CYCLE,
                    "Linking would form a cycle in the duplicate chain.");
        }

        // Insert the link record
        WorkOrderDuplicateLink link = WorkOrderDuplicateLink.create(sourceId, survivingRoot, reason, scope.userId());
        linkRepository.save(link);

        // Cancel source via transition service (applies guards, role checks, revision)
        transitionService.apply(source, WorkOrderEvent.CANCEL, scope.roles());

        // Mark excluded from SLA compliance denominator
        source.markExcludedFromSlaCompliance();
        workOrderRepository.save(source);

        // Publish domain event for timeline and customer-contact history
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "WORK_ORDER_DUPLICATE_LINKED",
                "WORK_ORDER",
                sourceId,
                now,
                MDC.get("traceId"),
                scope.userId(),
                new DuplicateLinkedPayload(sourceId, survivingRoot, reason)));

        log.info("duplicate_link_created source={} target={} surviving_root={} actor={}",
                sourceId, targetId, survivingRoot, scope.userId());

        return new DuplicateLinkResult(sourceId, WorkOrderStatus.CANCELLED,
                "DUPLICATE_REQUEST", survivingRoot, link.getLinkedAt());
    }

    /** Walks the link chain up to CHAIN_DEPTH_CAP hops to find the ultimate surviving root. */
    private UUID resolveRoot(UUID startId) {
        UUID current = startId;
        for (int depth = 0; depth < CHAIN_DEPTH_CAP; depth++) {
            UUID next = linkRepository.findTargetBySource(current).orElse(null);
            if (next == null) return current;
            if (next.equals(startId)) {
                throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_LINK_CYCLE,
                        "Cycle detected in existing duplicate chain.");
            }
            current = next;
        }
        throw new DuplicateLinkException(WorkOrderErrorCodes.DUPLICATE_LINK_CYCLE,
                "Duplicate chain exceeds maximum depth (" + CHAIN_DEPTH_CAP + ").");
    }

    /** Payload for the WORK_ORDER_DUPLICATE_LINKED domain event. */
    record DuplicateLinkedPayload(UUID sourceWorkOrderId, UUID survivingWorkOrderId, String reason) {}

    /** Return value for a successful link operation. */
    public record DuplicateLinkResult(
            UUID sourceWorkOrderId,
            WorkOrderStatus sourceState,
            String cancellationReasonCode,
            UUID targetWorkOrderId,
            Instant linkedAt) {}
}
