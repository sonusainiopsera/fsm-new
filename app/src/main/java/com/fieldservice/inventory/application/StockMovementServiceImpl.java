package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.StockMovementResult;
import com.fieldservice.inventory.api.StockMovementService;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLedger;
import com.fieldservice.inventory.domain.WorkOrderPart;
import com.fieldservice.inventory.repository.PartRepository;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.inventory.repository.WorkOrderPartRepository;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core stock movement implementation.
 *
 * <p>The fundamental primitive is a single-row conditional UPDATE:
 * {@code UPDATE stock_balance SET quantity_on_hand = quantity_on_hand - :qty
 *  WHERE part_id = :p AND location_id = :l AND quantity_on_hand >= :qty}
 * Zero affected rows means insufficient stock; the transaction rolls back immediately.
 *
 * <p>No isolation escalation, no pessimistic lock mode, no retry loop — per the
 * architecture mandate and ADR-0009.
 */
@Service
public class StockMovementServiceImpl implements StockMovementService {

    private static final Logger log = LoggerFactory.getLogger(StockMovementServiceImpl.class);

    private final StockBalanceRepository  stockBalanceRepository;
    private final WorkOrderPartRepository workOrderPartRepository;
    private final PartRepository          partRepository;
    private final EntityManager           entityManager;
    private final DomainEventPublisher    eventPublisher;

    public StockMovementServiceImpl(StockBalanceRepository stockBalanceRepository,
                                    WorkOrderPartRepository workOrderPartRepository,
                                    PartRepository partRepository,
                                    EntityManager entityManager,
                                    DomainEventPublisher eventPublisher) {
        this.stockBalanceRepository  = stockBalanceRepository;
        this.workOrderPartRepository = workOrderPartRepository;
        this.partRepository          = partRepository;
        this.entityManager           = entityManager;
        this.eventPublisher          = eventPublisher;
    }

    @Override
    @Transactional
    public StockMovementResult consumeParts(ConsumePartsCommand cmd) {
        validateCommand(cmd.lines(), cmd.workOrderId(), cmd.stockLocationId());

        Instant now = Instant.now();
        Map<UUID, Part> partMap = resolveParts(cmd.lines().stream()
                .map(ConsumePartsCommand.LineItem::partId).collect(Collectors.toList()));

        // First pass: check all balances exist; collect shortfalls before mutating anything
        List<InsufficientStockException.ShortfallLine> shortfalls = new ArrayList<>();
        for (ConsumePartsCommand.LineItem line : cmd.lines()) {
            StockBalance balance = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), cmd.stockLocationId())
                    .orElse(null);
            if (balance == null || balance.getQuantityOnHand() < line.quantity()) {
                int available = (balance == null) ? 0 : balance.getQuantityOnHand();
                shortfalls.add(new InsufficientStockException.ShortfallLine(
                        line.partId(), cmd.stockLocationId(), line.quantity(), available));
            }
        }
        if (!shortfalls.isEmpty()) {
            log.warn("consume_refused work_order_id={} shortfalls={} actor={}",
                    cmd.workOrderId(), shortfalls.size(), cmd.actorUserId());
            throw new InsufficientStockException(shortfalls);
        }

        // Second pass: apply conditional decrements; roll back everything on first zero-rows result
        List<StockMovementResult.LoggedLine> loggedLines = new ArrayList<>();
        for (ConsumePartsCommand.LineItem line : cmd.lines()) {
            int rowsUpdated = stockBalanceRepository.conditionalDecrement(
                    line.partId(), cmd.stockLocationId(), line.quantity());
            if (rowsUpdated == 0) {
                // Race: another transaction consumed the stock between our read and the update
                throw new InsufficientStockException(List.of(
                        new InsufficientStockException.ShortfallLine(
                                line.partId(), cmd.stockLocationId(), line.quantity(), 0)));
            }

            // Ledger entry
            StockLedger ledgerEntry = new StockLedger(
                    line.partId(), cmd.stockLocationId(),
                    -line.quantity(),
                    "WO:" + cmd.workOrderId());
            entityManager.persist(ledgerEntry);

            // Consumption record
            WorkOrderPart wop = new WorkOrderPart(
                    cmd.workOrderId(), line.partId(), cmd.stockLocationId(),
                    line.quantity(), "CONSUME", line.reasonCode(),
                    ledgerEntry.getId(), cmd.actorUserId(), now);
            workOrderPartRepository.save(wop);

            // Re-read resulting balance for response (balance was cleared by flushAutomatically)
            StockBalance updated = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), cmd.stockLocationId())
                    .orElseThrow();
            Part part = partMap.get(line.partId());
            loggedLines.add(new StockMovementResult.LoggedLine(
                    line.partId(),
                    part != null ? part.getPartNumber() : line.partId().toString(),
                    line.quantity(),
                    updated.getQuantityOnHand()));
        }

        // Outbox event (MANDATORY propagation — must be inside this transaction)
        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "PARTS_CONSUMED",
                "WORK_ORDER",
                cmd.workOrderId(),
                now,
                MDC.get("traceId"),
                cmd.actorUserId(),
                new PartsConsumedPayload(cmd.workOrderId(), cmd.stockLocationId(),
                        cmd.lines().size(), "CONSUME")));

        log.info("parts_consumed work_order_id={} location_id={} lines={} actor={}",
                cmd.workOrderId(), cmd.stockLocationId(), cmd.lines().size(), cmd.actorUserId());

        return new StockMovementResult(cmd.workOrderId(), loggedLines, now);
    }

    @Override
    @Transactional
    public StockMovementResult returnParts(ReturnPartsCommand cmd) {
        validateReturnCommand(cmd.lines(), cmd.workOrderId(), cmd.stockLocationId());

        Instant now = Instant.now();
        Map<UUID, Part> partMap = resolveParts(cmd.lines().stream()
                .map(ReturnPartsCommand.LineItem::partId).collect(Collectors.toList()));

        List<StockMovementResult.LoggedLine> loggedLines = new ArrayList<>();
        for (ReturnPartsCommand.LineItem line : cmd.lines()) {
            int rowsUpdated = stockBalanceRepository.increment(
                    line.partId(), cmd.stockLocationId(), line.quantity());
            if (rowsUpdated == 0) {
                throw new InvalidStockMovementException("lines",
                        "No balance row found for part " + line.partId()
                        + " at location " + cmd.stockLocationId());
            }

            StockLedger ledgerEntry = new StockLedger(
                    line.partId(), cmd.stockLocationId(),
                    line.quantity(),
                    "WO-RETURN:" + cmd.workOrderId());
            entityManager.persist(ledgerEntry);

            WorkOrderPart wop = new WorkOrderPart(
                    cmd.workOrderId(), line.partId(), cmd.stockLocationId(),
                    line.quantity(), "RETURN", line.reasonCode(),
                    ledgerEntry.getId(), cmd.actorUserId(), now);
            workOrderPartRepository.save(wop);

            StockBalance updated = stockBalanceRepository
                    .findByPartIdAndLocationId(line.partId(), cmd.stockLocationId())
                    .orElseThrow();
            Part part = partMap.get(line.partId());
            loggedLines.add(new StockMovementResult.LoggedLine(
                    line.partId(),
                    part != null ? part.getPartNumber() : line.partId().toString(),
                    line.quantity(),
                    updated.getQuantityOnHand()));
        }

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "PARTS_RETURNED",
                "WORK_ORDER",
                cmd.workOrderId(),
                now,
                MDC.get("traceId"),
                cmd.actorUserId(),
                new PartsConsumedPayload(cmd.workOrderId(), cmd.stockLocationId(),
                        cmd.lines().size(), "RETURN")));

        log.info("parts_returned work_order_id={} location_id={} lines={} actor={}",
                cmd.workOrderId(), cmd.stockLocationId(), cmd.lines().size(), cmd.actorUserId());

        return new StockMovementResult(cmd.workOrderId(), loggedLines, now);
    }

    // ---- helpers ---------------------------------------------------------------

    private void validateCommand(List<ConsumePartsCommand.LineItem> lines,
                                  UUID workOrderId, UUID locationId) {
        if (lines == null || lines.isEmpty()) {
            throw new InvalidStockMovementException("lines", "At least one line is required");
        }
        if (lines.size() > 50) {
            throw new InvalidStockMovementException("lines", "Maximum 50 lines per request");
        }
        for (int i = 0; i < lines.size(); i++) {
            ConsumePartsCommand.LineItem line = lines.get(i);
            if (line.quantity() <= 0) {
                throw new InvalidStockMovementException(
                        "lines[" + i + "].quantity",
                        "Quantity must be positive");
            }
        }
    }

    private void validateReturnCommand(List<ReturnPartsCommand.LineItem> lines,
                                        UUID workOrderId, UUID locationId) {
        if (lines == null || lines.isEmpty()) {
            throw new InvalidStockMovementException("lines", "At least one line is required");
        }
        if (lines.size() > 50) {
            throw new InvalidStockMovementException("lines", "Maximum 50 lines per request");
        }
        for (int i = 0; i < lines.size(); i++) {
            ReturnPartsCommand.LineItem line = lines.get(i);
            if (line.quantity() <= 0) {
                throw new InvalidStockMovementException(
                        "lines[" + i + "].quantity",
                        "Quantity must be positive");
            }
        }
    }

    private Map<UUID, Part> resolveParts(List<UUID> partIds) {
        return partRepository.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Function.identity()));
    }
}
