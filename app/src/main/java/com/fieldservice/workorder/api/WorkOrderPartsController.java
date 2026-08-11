package com.fieldservice.workorder.api;

import com.fieldservice.inventory.api.ConsumePartsCommand;
import com.fieldservice.inventory.api.ConsumePartsResult;
import com.fieldservice.inventory.api.ReturnPartsCommand;
import com.fieldservice.inventory.api.StockMovementService;
import com.fieldservice.platform.security.AccessScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
 * REST surface for parts consumption and returns against a work order.
 *
 * <p>Delegates exclusively to {@link StockMovementService} via the inventory public API.
 * This controller never imports inventory domain repositories or implementation classes
 * (enforced by {@code InventoryBoundaryTest}).
 *
 * <p>Idempotency is handled at the filter layer ({@code IdempotencyKeyFilter}) — replaying
 * the same {@code Idempotency-Key} returns the original response without re-executing.
 *
 * <p>See ADR-0008 for the resolution of 422 vs 409 for insufficient-stock responses.
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}/parts")
public class WorkOrderPartsController {

    private final StockMovementService stockMovementService;
    private final AccessScopeResolver scopeResolver;

    public WorkOrderPartsController(StockMovementService stockMovementService,
                                    AccessScopeResolver scopeResolver) {
        this.stockMovementService = stockMovementService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Log parts consumed from a technician's vehicle stock against a work order.
     *
     * <p>422 INSUFFICIENT_STOCK if any line lacks stock; 409 if work order state is invalid.
     * See ADR-0008.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConsumePartsResult> consumeParts(
            @PathVariable UUID id,
            @Valid @RequestBody ConsumeRequest request) {

        UUID actorId = scopeResolver.resolve().userId();

        ConsumePartsCommand command = new ConsumePartsCommand(
                id,
                request.locationId(),
                actorId,
                request.lines().stream()
                        .map(l -> new ConsumePartsCommand.ConsumptionLine(
                                l.partId(), l.quantity(), l.reasonCode()))
                        .toList());

        ConsumePartsResult result = stockMovementService.consumeParts(command);
        return ResponseEntity.ok(result);
    }

    /**
     * Return unused parts from a work order back to a vehicle stock location.
     */
    @PostMapping("/returns")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConsumePartsResult> returnParts(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest request) {

        UUID actorId = scopeResolver.resolve().userId();

        ReturnPartsCommand command = new ReturnPartsCommand(
                id,
                request.locationId(),
                actorId,
                request.lines().stream()
                        .map(l -> new ReturnPartsCommand.ReturnLine(
                                l.partId(), l.quantity(), l.reasonCode()))
                        .toList());

        ConsumePartsResult result = stockMovementService.returnParts(command);
        return ResponseEntity.ok(result);
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public record ConsumeRequest(
            @NotNull UUID locationId,
            @NotEmpty @Size(max = 50) List<@Valid ConsumeLineRequest> lines
    ) {}

    public record ConsumeLineRequest(
            @NotNull UUID partId,
            @Positive int quantity,
            @NotNull String reasonCode
    ) {}

    public record ReturnRequest(
            @NotNull UUID locationId,
            @NotEmpty @Size(max = 50) List<@Valid ReturnLineRequest> lines
    ) {}

    public record ReturnLineRequest(
            @NotNull UUID partId,
            @Positive int quantity,
            @NotNull String reasonCode
    ) {}
}
