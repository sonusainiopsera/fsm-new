package com.fieldservice.inventory.web;

import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.inventory.api.StockRecord;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only controller for stock balances.
 *
 * <p>Access control:
 * <ul>
 *   <li>CUSTOMER — 403, no existence disclosure.</li>
 *   <li>TECHNICIAN — row-scoped to their own vehicle location via the AccessScope predicate.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — all balances.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inventory/stock")
@Tag(name = "Inventory - Stock", description = "Stock balance read endpoints")
public class InventoryStockController {

    private final StockQueryService stockQueryService;

    public InventoryStockController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    @Operation(
            operationId = "listStock",
            summary = "List stock balances"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<StockRecord>> listStock(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID partId,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(stockQueryService.listStock(locationId, partId, pageQuery, request));
    }
}
