package com.fieldservice.workorder.api;

import com.fieldservice.domain.inventory.StockLocation;
import com.fieldservice.domain.inventory.StockLocationRepository;
import com.fieldservice.inventory.api.ConsumePartsCommand;
import com.fieldservice.inventory.api.ConsumePartsResult;
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
 * Mobile-optimised parts consumption endpoint for technicians (WO-157).
 *
 * <p>Automatically resolves the technician's active vehicle stock location from the
 * {@code technicianId} claim in the JWT, so the client does not need to supply a
 * {@code locationId}. The simplified body matches the mobile API contract:
 * {@code { lines: [ { partId, quantity } ] }}.
 *
 * <p>422 INSUFFICIENT_STOCK is returned when any line exceeds available stock; the
 * field errors identify the exact line and quantities so the UI can name the short part
 * without guessing.
 *
 * <p>Idempotency is handled at the filter layer ({@code IdempotencyKeyFilter}).
 */
@RestController
@RequestMapping("/api/v1/work-orders/{id}/parts-consumption")
@PreAuthorize("isAuthenticated()")
public class TechnicianPartsConsumptionController {

    private static final String USED_ON_JOB = "USED_ON_JOB";

    private final StockMovementService stockMovementService;
    private final StockLocationRepository stockLocationRepository;
    private final AccessScopeResolver scopeResolver;

    public TechnicianPartsConsumptionController(StockMovementService stockMovementService,
                                                StockLocationRepository stockLocationRepository,
                                                AccessScopeResolver scopeResolver) {
        this.stockMovementService = stockMovementService;
        this.stockLocationRepository = stockLocationRepository;
        this.scopeResolver = scopeResolver;
    }

    @PostMapping
    public ResponseEntity<ConsumePartsResult> consumeParts(
            @PathVariable UUID id,
            @Valid @RequestBody ConsumptionRequest request) {

        UUID technicianId = scopeResolver.resolve().technicianId();
        UUID actorId = scopeResolver.resolve().userId();

        // Resolve active vehicle location for this technician
        StockLocation location = stockLocationRepository
                .findFirstByTechnicianIdAndActiveTrue(technicianId)
                .orElseThrow(() -> new com.fieldservice.platform.exception.NotFoundException(
                        "No active vehicle stock location found for this technician"));

        ConsumePartsCommand command = new ConsumePartsCommand(
                id,
                location.getId(),
                actorId,
                request.lines().stream()
                        .map(l -> new ConsumePartsCommand.ConsumptionLine(
                                l.partId(), l.quantity(), USED_ON_JOB))
                        .toList());

        ConsumePartsResult result = stockMovementService.consumeParts(command);
        return ResponseEntity.ok(result);
    }

    public record ConsumptionRequest(
            @NotEmpty @Size(max = 50) List<@Valid ConsumptionLine> lines
    ) {}

    public record ConsumptionLine(
            @NotNull UUID partId,
            @Positive int quantity
    ) {}
}
