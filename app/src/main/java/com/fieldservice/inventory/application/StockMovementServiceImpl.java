package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockBalanceRepository;
import com.fieldservice.domain.inventory.StockLedger;
import com.fieldservice.domain.inventory.StockLedgerRepository;
import com.fieldservice.domain.inventory.StockLocation;
import com.fieldservice.domain.inventory.StockLocationRepository;
import com.fieldservice.domain.inventory.WorkOrderPart;
import com.fieldservice.domain.inventory.WorkOrderPartRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.inventory.api.ConsumePartsCommand;
import com.fieldservice.inventory.api.ConsumePartsResult;
import com.fieldservice.inventory.api.InsufficientStockException;
import com.fieldservice.inventory.api.InvalidMovementException;
import com.fieldservice.inventory.api.ReturnPartsCommand;
import com.fieldservice.inventory.api.StockMovementService;
import com.fieldservice.outbox.payload.PartsConsumedPayload;
import com.fieldservice.outbox.payload.PartsReturnedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.exception.ForbiddenException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.exception.IllegalTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * Sole implementation of {@link StockMovementService}.
 *
 * <p>This class is the <em>only</em> component that writes to stock tables (StockBalance,
 * StockLedger, WorkOrderPart). No other service, controller, or repository may write
 * stock directly (enforced by {@code InventoryBoundaryTest}).
 *
 * <p>Core invariant: balance decrement, consumption record, Envers revision, and outbox
 * event all commit in one {@code @Transactional} boundary. A rollback yields no residue.
 *
 * <p>No SERIALIZABLE/REPEATABLE READ isolation, no pessimistic locking, no retry loop.
 * The conditional {@code UPDATE ... WHERE quantity_on_hand >= :qty} is the sole mechanism
 * for BR-16 enforcement at the application layer; the {@code stock_non_negative} CHECK
 * constraint is the structural backstop.
 */
@Service
@Transactional
class StockMovementServiceImpl implements StockMovementService {

    private static final Logger log = LoggerFactory.getLogger(StockMovementServiceImpl.class);

    /** Work order states that permit field consumption. */
    private static final Set<WorkOrderState> FIELD_EXECUTION_STATES = Set.of(
            WorkOrderState.ASSIGNED,
            WorkOrderState.EN_ROUTE,
            WorkOrderState.IN_PROGRESS,
            WorkOrderState.ON_HOLD
    );

    private static final int MAX_LINES_PER_REQUEST = 50;

    private final StockBalanceRepository stockBalanceRepository;
    private final StockLedgerRepository stockLedgerRepository;
    private final WorkOrderPartRepository workOrderPartRepository;
    private final PartRepository partRepository;
    private final WorkOrderRepository workOrderRepository;
    private final StockLocationRepository stockLocationRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final AccessScopeResolver scopeResolver;
    private final DomainEventPublisher eventPublisher;
    private final InventoryMetrics inventoryMetrics;

    StockMovementServiceImpl(
            StockBalanceRepository stockBalanceRepository,
            StockLedgerRepository stockLedgerRepository,
            WorkOrderPartRepository workOrderPartRepository,
            PartRepository partRepository,
            WorkOrderRepository workOrderRepository,
            StockLocationRepository stockLocationRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            AccessScopeResolver scopeResolver,
            DomainEventPublisher eventPublisher,
            InventoryMetrics inventoryMetrics) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockLedgerRepository = stockLedgerRepository;
        this.workOrderPartRepository = workOrderPartRepository;
        this.partRepository = partRepository;
        this.workOrderRepository = workOrderRepository;
        this.stockLocationRepository = stockLocationRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.scopeResolver = scopeResolver;
        this.eventPublisher = eventPublisher;
        this.inventoryMetrics = inventoryMetrics;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ConsumePartsResult consumeParts(ConsumePartsCommand command) {
        validateLineCount(command.lines().size());

        AccessScope scope = scopeResolver.resolve();
        WorkOrder workOrder = loadScopedWorkOrder(command.workOrderId());
        enforceFieldExecutionState(workOrder);
        enforceLocationOwnership(scope, command.locationId());

        // Sort lines deterministically by partId for predictable log ordering
        List<ConsumePartsCommand.ConsumptionLine> sorted = command.lines().stream()
                .sorted(Comparator.comparing(l -> l.partId().toString()))
                .toList();

        // Resolve all parts upfront (fail fast on missing parts before touching balances)
        Map<UUID, Part> partsById = resolvePartsById(sorted.stream()
                .map(ConsumePartsCommand.ConsumptionLine::partId).toList());

        // Collect all failures before touching any balance (all-or-nothing)
        List<InsufficientStockException.LineDetail> failures = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            ConsumePartsCommand.ConsumptionLine line = sorted.get(i);
            if (line.quantity() <= 0) {
                throw new InvalidMovementException(
                        "lines[" + i + "].quantity must be positive, got " + line.quantity());
            }
            StockBalance balance = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), command.locationId())
                    .orElse(null);
            int available = balance != null ? balance.getQuantityOnHand() : 0;
            if (available < line.quantity()) {
                failures.add(new InsufficientStockException.LineDetail(
                        "lines[" + i + "].quantity",
                        line.partId(), command.locationId(),
                        line.quantity(), available));
            }
        }

        if (!failures.isEmpty()) {
            log.info("consumeParts.refused: workOrderId={}, failureCount={}, actor={}, traceId={}",
                    command.workOrderId(), failures.size(), command.actorUserId(), MDC.get("traceId"));
            throw new InsufficientStockException(failures);
        }

        // Apply all lines — at this point we expect all conditional decrements to succeed
        Instant now = Instant.now();
        List<ConsumePartsResult.LoggedLine> loggedLines = new ArrayList<>();
        List<PartsConsumedPayload.LineItem> eventLines = new ArrayList<>();

        for (ConsumePartsCommand.ConsumptionLine line : sorted) {
            int affected = stockBalanceRepository.conditionalDecrement(
                    line.partId(), command.locationId(), line.quantity());

            if (affected == 0) {
                // Concurrent decrement beat us — roll back everything
                log.warn("consumeParts.concurrent_race: partId={}, locationId={}, actor={}, traceId={}",
                        line.partId(), command.locationId(), command.actorUserId(), MDC.get("traceId"));
                throw new InsufficientStockException(List.of(new InsufficientStockException.LineDetail(
                        "lines[0].quantity", line.partId(), command.locationId(), line.quantity(), 0)));
            }

            // Post-decrement balance (approximate — the conditional UPDATE already committed it)
            StockBalance balance = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), command.locationId())
                    .orElseThrow(() -> new IllegalStateException("Balance disappeared after decrement"));

            // Write ledger entry
            StockLedger ledger = buildLedgerEntry(line.partId(), command.locationId(),
                    command.workOrderId(), command.actorUserId(), -line.quantity(),
                    "CONSUMPTION", now);
            StockLedger savedLedger = stockLedgerRepository.save(ledger);

            // Write work_order_part record
            WorkOrderPart wop = buildWorkOrderPart(command.workOrderId(), line.partId(),
                    command.locationId(), line.quantity(), line.reasonCode(),
                    savedLedger.getId(), command.actorUserId(), now);
            workOrderPartRepository.save(wop);

            Part part = partsById.get(line.partId());
            String partNumber = part != null ? part.getPartNumber() : null;

            loggedLines.add(new ConsumePartsResult.LoggedLine(
                    line.partId(), partNumber, line.quantity(), balance.getQuantityOnHand()));
            eventLines.add(new PartsConsumedPayload.LineItem(
                    line.partId(), line.quantity(), balance.getQuantityOnHand()));
        }

        publishPartsConsumed(command.workOrderId(), command.locationId(),
                eventLines, command.actorUserId(), now);

        log.info("consumeParts.applied: workOrderId={}, lineCount={}, actor={}, traceId={}",
                command.workOrderId(), loggedLines.size(), command.actorUserId(), MDC.get("traceId"));

        return new ConsumePartsResult(command.workOrderId(), loggedLines, "PENDING");
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ConsumePartsResult returnParts(ReturnPartsCommand command) {
        validateLineCount(command.lines().size());

        AccessScope scope = scopeResolver.resolve();
        WorkOrder workOrder = loadScopedWorkOrder(command.workOrderId());
        enforceFieldExecutionState(workOrder);
        enforceLocationOwnership(scope, command.locationId());

        List<ReturnPartsCommand.ReturnLine> sorted = command.lines().stream()
                .sorted(Comparator.comparing(l -> l.partId().toString()))
                .toList();

        Map<UUID, Part> partsById = resolvePartsById(sorted.stream()
                .map(ReturnPartsCommand.ReturnLine::partId).toList());

        Instant now = Instant.now();
        List<ConsumePartsResult.LoggedLine> loggedLines = new ArrayList<>();
        List<PartsReturnedPayload.LineItem> eventLines = new ArrayList<>();

        for (int i = 0; i < sorted.size(); i++) {
            ReturnPartsCommand.ReturnLine line = sorted.get(i);
            if (line.quantity() <= 0) {
                throw new InvalidMovementException(
                        "lines[" + i + "].quantity must be positive, got " + line.quantity());
            }

            int affected = stockBalanceRepository.increment(
                    line.partId(), command.locationId(), line.quantity());

            if (affected == 0) {
                throw new NotFoundException("StockBalance",
                        line.partId() + "@" + command.locationId());
            }

            StockBalance balance = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), command.locationId())
                    .orElseThrow(() -> new IllegalStateException("Balance disappeared after increment"));

            StockLedger ledger = buildLedgerEntry(line.partId(), command.locationId(),
                    command.workOrderId(), command.actorUserId(), line.quantity(),
                    "RETURN", now);
            StockLedger savedLedger = stockLedgerRepository.save(ledger);

            WorkOrderPart wop = buildWorkOrderPart(command.workOrderId(), line.partId(),
                    command.locationId(), -line.quantity(), line.reasonCode(),
                    savedLedger.getId(), command.actorUserId(), now);
            workOrderPartRepository.save(wop);

            Part part = partsById.get(line.partId());
            String partNumber = part != null ? part.getPartNumber() : null;

            loggedLines.add(new ConsumePartsResult.LoggedLine(
                    line.partId(), partNumber, line.quantity(), balance.getQuantityOnHand()));
            eventLines.add(new PartsReturnedPayload.LineItem(
                    line.partId(), line.quantity(), balance.getQuantityOnHand()));
        }

        publishPartsReturned(command.workOrderId(), command.locationId(),
                eventLines, command.actorUserId(), now);

        log.info("returnParts.applied: workOrderId={}, lineCount={}, actor={}, traceId={}",
                command.workOrderId(), loggedLines.size(), command.actorUserId(), MDC.get("traceId"));

        return new ConsumePartsResult(command.workOrderId(), loggedLines, "PENDING");
    }

    @Override
    @PreAuthorize("hasAnyRole('DISPATCHER','ADMIN')")
    public void transferStock(UUID sourceLocationId, UUID destinationLocationId,
                              UUID partId, int quantity, UUID actorUserId) {
        if (sourceLocationId.equals(destinationLocationId)) {
            throw new InvalidMovementException("Source and destination locations must differ");
        }
        if (quantity <= 0) {
            throw new InvalidMovementException("Transfer quantity must be positive");
        }

        int affected = stockBalanceRepository.conditionalDecrement(partId, sourceLocationId, quantity);
        if (affected == 0) {
            StockBalance balance = stockBalanceRepository
                    .findByPartIdAndLocationId(partId, sourceLocationId).orElse(null);
            int available = balance != null ? balance.getQuantityOnHand() : 0;
            throw new InsufficientStockException(List.of(new InsufficientStockException.LineDetail(
                    "quantity", partId, sourceLocationId, quantity, available)));
        }

        stockBalanceRepository.increment(partId, destinationLocationId, quantity);

        Instant now = Instant.now();
        UUID correlationId = UUID.randomUUID();
        stockLedgerRepository.save(buildLedgerEntry(partId, sourceLocationId, destinationLocationId,
                null, actorUserId, -quantity, "TRANSFER_OUT", null, correlationId, now));
        stockLedgerRepository.save(buildLedgerEntry(partId, destinationLocationId, sourceLocationId,
                null, actorUserId, quantity, "TRANSFER_IN", null, correlationId, now));
    }

    @Override
    @PreAuthorize("hasAnyRole('DISPATCHER','ADMIN')")
    public void adjustStock(UUID locationId, UUID partId, int delta, UUID actorUserId) {
        if (delta == 0) {
            throw new InvalidMovementException("Adjustment delta must be non-zero");
        }

        if (delta < 0) {
            int affected = stockBalanceRepository.conditionalDecrement(partId, locationId, -delta);
            if (affected == 0) {
                StockBalance balance = stockBalanceRepository
                        .findByPartIdAndLocationId(partId, locationId).orElse(null);
                int available = balance != null ? balance.getQuantityOnHand() : 0;
                throw new InsufficientStockException(List.of(new InsufficientStockException.LineDetail(
                        "delta", partId, locationId, -delta, available)));
            }
        } else {
            stockBalanceRepository.increment(partId, locationId, delta);
        }

        stockLedgerRepository.save(buildLedgerEntry(partId, locationId, null,
                actorUserId, delta, "ADJUSTMENT", Instant.now()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WorkOrder loadScopedWorkOrder(UUID workOrderId) {
        return scopedQueryExecutor.findById(WorkOrder.class, workOrderId, workOrderRepository);
    }

    private void enforceFieldExecutionState(WorkOrder workOrder) {
        if (!FIELD_EXECUTION_STATES.contains(workOrder.getState())) {
            throw new IllegalTransitionException(workOrder.getState().name(), "CONSUME_PARTS");
        }
    }

    private void enforceLocationOwnership(AccessScope scope, UUID locationId) {
        boolean isTechnician = scope.roles().stream()
                .anyMatch(r -> r.equals("ROLE_TECHNICIAN") || r.equals("TECHNICIAN"));
        if (!isTechnician) {
            return; // DISPATCHER/ADMIN have no location restriction
        }
        if (scope.technicianId() == null) {
            throw new ForbiddenException("Technician identity not resolved");
        }
        StockLocation location = stockLocationRepository.findById(locationId)
                .orElseThrow(() -> new NotFoundException("StockLocation", locationId));
        if (!scope.technicianId().equals(location.getTechnicianId())) {
            throw new ForbiddenException("Technician does not own location " + locationId);
        }
    }

    private Map<UUID, Part> resolvePartsById(List<UUID> partIds) {
        return partRepository.findAllById(partIds).stream()
                .collect(toMap(Part::getId, Function.identity()));
    }

    private static void validateLineCount(int count) {
        if (count == 0) {
            throw new InvalidMovementException("Request must contain at least one line");
        }
        if (count > MAX_LINES_PER_REQUEST) {
            throw new InvalidMovementException(
                    "Request exceeds maximum of " + MAX_LINES_PER_REQUEST + " lines");
        }
    }

    private StockLedger buildLedgerEntry(UUID partId, UUID locationId,
                                          UUID workOrderId, UUID actorUserId,
                                          int delta, String movementType, Instant at) {
        return buildLedgerEntry(partId, locationId, null, workOrderId, actorUserId,
                delta, movementType, null, null, at);
    }

    private StockLedger buildLedgerEntry(UUID partId, UUID fromLocationId, UUID toLocationId,
                                          UUID workOrderId, UUID actorUserId,
                                          int delta, String movementType,
                                          Integer resultingQuantity, UUID correlationId,
                                          Instant at) {
        StockLedger entry = new StockLedger();
        entry.setPartId(partId);
        entry.setLocationId(fromLocationId != null ? fromLocationId : toLocationId);
        entry.setFromLocationId(fromLocationId);
        entry.setToLocationId(toLocationId);
        entry.setWorkOrderId(workOrderId);
        entry.setTechnicianId(actorUserId);
        entry.setActorUserId(actorUserId);
        entry.setQuantityDelta(delta);
        entry.setMovementType(movementType);
        entry.setResultingQuantity(resultingQuantity);
        entry.setCorrelationId(correlationId);
        entry.setOccurredAt(at);
        inventoryMetrics.incrementLedgerEntriesWritten();
        return entry;
    }

    private WorkOrderPart buildWorkOrderPart(UUID workOrderId, UUID partId, UUID locationId,
                                              int quantity, String reasonCode, UUID ledgerEntryId,
                                              UUID actorUserId, Instant at) {
        WorkOrderPart wop = new WorkOrderPart();
        wop.setWorkOrderId(workOrderId);
        wop.setPartId(partId);
        wop.setStockLocationId(locationId);
        wop.setQuantity(quantity);
        wop.setReasonCode(reasonCode);
        wop.setLedgerEntryId(ledgerEntryId);
        wop.setActorUserId(actorUserId);
        wop.setOccurredAt(at);
        return wop;
    }

    private void publishPartsConsumed(UUID workOrderId, UUID locationId,
                                       List<PartsConsumedPayload.LineItem> lines,
                                       UUID actorUserId, Instant at) {
        var payload = new PartsConsumedPayload(workOrderId, locationId, lines, actorUserId, at);
        eventPublisher.publish(DomainEvent.of(
                PartsConsumedPayload.EVENT_TYPE,
                PartsConsumedPayload.AGGREGATE_TYPE,
                workOrderId, at, MDC.get("traceId"), actorUserId,
                PiiRedactionUtility.toPayloadMap(payload)));
    }

    private void publishPartsReturned(UUID workOrderId, UUID locationId,
                                       List<PartsReturnedPayload.LineItem> lines,
                                       UUID actorUserId, Instant at) {
        var payload = new PartsReturnedPayload(workOrderId, locationId, lines, actorUserId, at);
        eventPublisher.publish(DomainEvent.of(
                PartsReturnedPayload.EVENT_TYPE,
                PartsReturnedPayload.AGGREGATE_TYPE,
                workOrderId, at, MDC.get("traceId"), actorUserId,
                PiiRedactionUtility.toPayloadMap(payload)));
    }
}
