package com.fieldservice.inventory.web;

import com.fieldservice.inventory.api.PartRecord;
import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only controller for the parts catalog.
 *
 * <p>Access control: CUSTOMER is denied 403 via {@code @PreAuthorize} in the service.
 * All other authenticated roles receive the full catalog.
 */
@RestController
@RequestMapping("/api/v1/inventory/parts")
@Tag(name = "Inventory - Parts", description = "Parts catalog read endpoints")
public class InventoryPartsController {

    private final StockQueryService stockQueryService;

    public InventoryPartsController(StockQueryService stockQueryService) {
        this.stockQueryService = stockQueryService;
    }

    @Operation(
            operationId = "listParts",
            summary = "List parts catalog"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<PartRecord>> listParts(
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(stockQueryService.listParts(pageQuery, request));
    }
}
