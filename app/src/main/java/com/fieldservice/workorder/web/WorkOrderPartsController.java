package com.fieldservice.workorder.web;

import com.fieldservice.inventory.api.StockMovementResult;
import com.fieldservice.inventory.api.StockMovementService;
import com.fieldservice.inventory.application.ConsumePartsCommand;
import com.fieldservice.inventory.application.ReturnPartsCommand;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.inventory.repository.StockLocationRepository;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Exposes parts consumption and return endpoints on a work order.
 *
 * <p>Controllers never call stock repositories directly — all mutations go through
 * {@link StockMovementService} (the sole writer of stock tables per AC-1).
 *
 * <p>Security:
 * <ul>
 *   <li>TECHNICIAN: may only consume/return against their own vehicle stock location.
 *       Any other location ID in the request is rejected 403.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER: may use any location.</li>
 *   <li>CUSTOMER: denied outright at @PreAuthorize.</li>
 * </ul>
 *
 * <p>See ADR-0009: insufficient stock → HTTP 422 (not 409).
 */
@RestController
@RequestMapping("/api/v1/work-orders/{workOrderId}/parts")
public class WorkOrderPartsController {

    private final StockMovementService     stockMovementService;
    private final RequestScopedAccessScope accessScope;
    private final StockLocationRepository  stockLocationRepository;

    public WorkOrderPartsController(StockMovementService stockMovementService,
                                    RequestScopedAccessScope accessScope,
                                    StockLocationRepository stockLocationRepository) {
        this.stockMovementService    = stockMovementService;
        this.accessScope             = accessScope;
        this.stockLocationRepository = stockLocationRepository;
    }

    /**
     * Logs parts consumption from vehicle stock against a work order.
     *
     * <p>All lines succeed or all are rejected. Insufficient stock → 422 INSUFFICIENT_STOCK.
     * Illegal work order state → 409 WORK_ORDER_ILLEGAL_TRANSITION.
     * Idempotency handled transparently by the platform IdempotencyFilter.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<PartsMovementResponse> consumeParts(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody ConsumePartsRequest request) {

        AccessScope scope = accessScope.get();
        UUID resolvedLocationId = resolveAndAuthoriseLocation(scope, request.stockLocationId());

        List<ConsumePartsCommand.LineItem> lines = request.lines().stream()
                .map(l -> new ConsumePartsCommand.LineItem(l.partId(), l.quantity(), l.reasonCode()))
                .toList();

        ConsumePartsCommand cmd = new ConsumePartsCommand(
                workOrderId, resolvedLocationId, lines, scope.userId(), null);

        StockMovementResult result = stockMovementService.consumeParts(cmd);
        return ResponseEntity.ok(PartsMovementResponse.from(result));
    }

    /**
     * Returns unused parts from vehicle stock back against a work order.
     */
    @PostMapping("/returns")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<PartsMovementResponse> returnParts(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody ReturnPartsRequest request) {

        AccessScope scope = accessScope.get();
        UUID resolvedLocationId = resolveAndAuthoriseLocation(scope, request.stockLocationId());

        List<ReturnPartsCommand.LineItem> lines = request.lines().stream()
                .map(l -> new ReturnPartsCommand.LineItem(l.partId(), l.quantity(), l.reasonCode()))
                .toList();

        ReturnPartsCommand cmd = new ReturnPartsCommand(
                workOrderId, resolvedLocationId, lines, scope.userId(), null);

        StockMovementResult result = stockMovementService.returnParts(cmd);
        return ResponseEntity.ok(PartsMovementResponse.from(result));
    }

    /**
     * Resolves the effective stock location ID, enforcing TECHNICIAN scope:
     * a technician may only use their own vehicle location.
     *
     * <p>If the request does not supply a locationId and the caller is a TECHNICIAN,
     * the vehicle location is auto-resolved from the JWT technicianId claim.
     */
    private UUID resolveAndAuthoriseLocation(AccessScope scope, UUID requestedLocationId) {
        if (scope.isTechnician()) {
            UUID vehicleLocationId = scope.technicianId() == null ? null :
                    stockLocationRepository.findByTechnicianId(scope.technicianId())
                            .map(StockLocation::getId)
                            .orElse(null);

            if (vehicleLocationId == null) {
                throw new ScopedAccessDeniedException(
                        "stock_location",
                        "No vehicle stock location configured for this technician");
            }

            if (requestedLocationId != null && !vehicleLocationId.equals(requestedLocationId)) {
                throw new ScopedAccessDeniedException(
                        "stock_location",
                        "Technician may only access their own vehicle stock location");
            }
            return vehicleLocationId;
        }
        // Privileged roles: must supply a locationId
        if (requestedLocationId == null) {
            throw new com.fieldservice.inventory.application.InvalidStockMovementException(
                    "stockLocationId", "stockLocationId is required");
        }
        return requestedLocationId;
    }
}
