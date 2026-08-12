package com.fieldservice.workorder.duplicates;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderDuplicateLink;
import com.fieldservice.domain.workorder.WorkOrderDuplicateLinkRepository;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.workorder.WorkOrderTransitionService;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Links a duplicate work order to its surviving counterpart and cancels it
 * through the existing transition service.
 *
 * <p>Validation order (all failures return 422 with distinct codes):
 * <ol>
 *   <li>Self-link check.</li>
 *   <li>Row-scope check on both source and target (403 on failure).</li>
 *   <li>Source must be in an open (non-terminal) state.</li>
 *   <li>Source must not already be linked.</li>
 *   <li>Target must be in an open state.</li>
 *   <li>Cycle / chain resolution with depth cap.</li>
 * </ol>
 *
 * <p>Persistence order (single transaction):
 * <ol>
 *   <li>Insert {@link WorkOrderDuplicateLink}.</li>
 *   <li>Apply CANCEL event to source via {@link WorkOrderTransitionService#applyEvent}.</li>
 *   <li>Set {@code excluded_from_sla_compliance = true} on source.</li>
 * </ol>
 */
@Service
@Transactional
public class DuplicateLinkServiceImpl implements DuplicateLinkService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateLinkServiceImpl.class);

    static final int MAX_CHAIN_DEPTH = 10;
    static final String CANCELLATION_REASON_CODE = "DUPLICATE_REQUEST";

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderDuplicateLinkRepository linkRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final WorkOrderTransitionService transitionService;
    private final AccessScopeResolver scopeResolver;

    public DuplicateLinkServiceImpl(
            WorkOrderRepository workOrderRepository,
            WorkOrderDuplicateLinkRepository linkRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            WorkOrderTransitionService transitionService,
            AccessScopeResolver scopeResolver) {
        this.workOrderRepository    = workOrderRepository;
        this.linkRepository         = linkRepository;
        this.scopedQueryExecutor    = scopedQueryExecutor;
        this.transitionService      = transitionService;
        this.scopeResolver          = scopeResolver;
    }

    @Override
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'ADMIN')")
    public LinkResult link(UUID sourceId, UUID targetId, String reason) {
        if (sourceId.equals(targetId)) {
            throw DuplicateLinkException.selfLink();
        }

        // Load both via scope — absent-or-out-of-scope returns 403 (non-disclosure)
        WorkOrder source = scopedQueryExecutor.findById(WorkOrder.class, sourceId, workOrderRepository);
        WorkOrder target = scopedQueryExecutor.findById(WorkOrder.class, targetId, workOrderRepository);

        // Source must be cancellable (open state)
        if (isTerminal(source.getState())) {
            throw new DuplicateLinkException("DUPLICATE_ALREADY_LINKED",
                    "Source work order " + source.getReference() + " is already in a terminal state.");
        }

        // Source must not already be linked
        if (linkRepository.existsBySourceWorkOrderId(sourceId)) {
            throw DuplicateLinkException.alreadyLinked(source.getReference());
        }

        // Target must be open
        if (isTerminal(target.getState())) {
            throw DuplicateLinkException.targetNotOpen(target.getReference());
        }

        // Resolve surviving root, detecting cycles
        UUID survivingRoot = resolveSurvivingRoot(targetId);

        // Safety check: if root resolution returns sourceId, it would be a self-link / cycle
        if (survivingRoot.equals(sourceId)) {
            throw DuplicateLinkException.cycle();
        }

        UUID actor = resolveActorId();
        Instant linkedAt = Instant.now();

        // Insert link row (append-only)
        WorkOrderDuplicateLink link = new WorkOrderDuplicateLink(
                sourceId, survivingRoot, reason, actor, linkedAt);
        linkRepository.save(link);

        // Cancel source through the transition service (inherits guards, outbox, audit)
        transitionService.applyEvent(sourceId, WorkOrderEvent.CANCEL);

        // Mark source as excluded from SLA compliance denominator
        source.setExcludedFromSlaCompliance(true);
        workOrderRepository.save(source);

        log.info("duplicate.linked: sourceId={}, survivingRoot={}, actor={}, linkedAt={}",
                sourceId, survivingRoot, actor, linkedAt);

        return new LinkResult(
                sourceId,
                WorkOrderState.CANCELLED.name(),
                CANCELLATION_REASON_CODE,
                survivingRoot,
                linkedAt);
    }

    /**
     * Walks the link chain from {@code startId} to find the surviving root.
     *
     * <p>The root is the work order that is not itself a source in any existing link.
     * If the chain contains {@code startId} (cycle), throws {@link DuplicateLinkException#cycle()}.
     * If depth exceeds {@value #MAX_CHAIN_DEPTH}, throws a cycle exception to fail-safe.
     *
     * @param startId the target work order ID specified by the caller
     * @return the surviving root ID (may equal startId if startId has no outbound link)
     */
    UUID resolveSurvivingRoot(UUID startId) {
        UUID current = startId;
        Set<UUID> visited = new HashSet<>();
        visited.add(current);

        for (int depth = 0; depth < MAX_CHAIN_DEPTH; depth++) {
            Optional<WorkOrderDuplicateLink> outbound = linkRepository.findBySourceWorkOrderId(current);
            if (outbound.isEmpty()) {
                return current; // no further link — current is the root
            }
            UUID next = outbound.get().getTargetWorkOrderId();
            if (!visited.add(next)) {
                throw DuplicateLinkException.cycle();
            }
            current = next;
        }
        // Depth cap reached — treat as cycle to prevent pathological chains
        throw DuplicateLinkException.cycle();
    }

    private static boolean isTerminal(WorkOrderState state) {
        return state == WorkOrderState.COMPLETED
                || state == WorkOrderState.CLOSED
                || state == WorkOrderState.CANCELLED;
    }

    private UUID resolveActorId() {
        try {
            return scopeResolver.resolve().userId();
        } catch (Exception ex) {
            return null;
        }
    }
}
