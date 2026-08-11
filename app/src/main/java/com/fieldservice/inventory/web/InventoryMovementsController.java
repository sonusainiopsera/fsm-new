package com.fieldservice.inventory.web;

import com.fieldservice.inventory.api.MovementRecord;
import com.fieldservice.inventory.application.MovementQueryService;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only movement history endpoint.
 *
 * <p>GET /api/v1/inventory/movements — paginated, filterable stock ledger view.
 * CUSTOMER 403; TECHNICIAN scoped to vehicle location; DISPATCHER/ADMIN/MANAGER see all.
 */
@RestController
@RequestMapping("/api/v1/inventory/movements")
@Tag(name = "Inventory - Movements", description = "Stock ledger movement history")
public class InventoryMovementsController {

    private final MovementQueryService movementQueryService;

    public InventoryMovementsController(MovementQueryService movementQueryService) {
        this.movementQueryService = movementQueryService;
    }

    @Operation(
            operationId = "listMovements",
            summary = "List stock ledger movements (paginated, filterable)"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<MovementRecord>> listMovements(
            @Nullable @RequestParam(required = false) UUID partId,
            @Nullable @RequestParam(required = false) UUID locationId,
            @Nullable @RequestParam(required = false) UUID workOrderId,
            @Nullable @RequestParam(required = false) String movementType,
            @Nullable @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Nullable @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            PageQuery pageQuery,
            HttpServletRequest request) {

        return ResponseEntity.ok(movementQueryService.listMovements(
                partId, locationId, workOrderId, movementType, from, to, pageQuery, request));
    }
}
