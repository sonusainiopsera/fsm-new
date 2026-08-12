package com.fieldservice.workorder.api;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockBalanceRepository;
import com.fieldservice.pagination.SpecificationPageService;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mobile-optimised vehicle stock read endpoint for the authenticated technician (WO-157).
 *
 * <p>Enforces the TECHNICIAN AccessScope predicate via {@link SpecificationPageService} so
 * a TECHNICIAN caller sees only stock held at their own vehicle location.
 */
@RestController
@RequestMapping("/api/v1/technicians/me/stock")
@Tag(name = "Technician", description = "Technician vehicle stock endpoint")
@PreAuthorize("hasAnyAuthority('TECHNICIAN','DISPATCHER','ADMIN')")
public class TechnicianStockController {

    private static final SortAllowList STOCK_SORTS = SortAllowList.of(
            "quantityOnHand", "quantityOnHand",
            "partCode",        "partCode"
    );

    private final SpecificationPageService pageService;
    private final StockBalanceRepository stockBalanceRepository;
    private final PartRepository partRepository;

    public TechnicianStockController(SpecificationPageService pageService,
                                     StockBalanceRepository stockBalanceRepository,
                                     PartRepository partRepository) {
        this.pageService = pageService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.partRepository = partRepository;
    }

    @GetMapping
    @Operation(operationId = "listMyStock",
               summary = "List vehicle stock lines for the authenticated technician")
    public ResponseEntity<PagedResponse<VehicleStockItem>> listMyStock(
            PageQuery pageQuery, HttpServletRequest request) {

        PagedResponse<StockBalance> page = pageService.findPage(
                StockBalance.class, null, pageQuery, STOCK_SORTS,
                stockBalanceRepository, "technician_stock", request);

        // Batch-fetch part catalogue data
        List<UUID> partIds = page.data().stream()
                .map(StockBalance::getPartId).distinct().toList();
        Map<UUID, Part> partsById = partRepository.findAllById(partIds)
                .stream().collect(Collectors.toMap(Part::getId, p -> p));

        List<VehicleStockItem> items = page.data().stream()
                .map(b -> {
                    Part part = partsById.get(b.getPartId());
                    return new VehicleStockItem(
                            b.getPartId(),
                            part != null ? part.getPartNumber() : "UNKNOWN",
                            part != null ? part.getDescription() : null,
                            b.getQuantityOnHand(),
                            part != null ? part.getUnit() : "EACH"
                    );
                }).toList();

        return ResponseEntity.ok(new PagedResponse<>(items, page.page(), page.links()));
    }

    /**
     * Simplified stock view for mobile parts selection.
     *
     * @param partId          the part catalogue identifier
     * @param partCode        the human-readable part number / SKU
     * @param description     short part description
     * @param quantityOnHand  current available quantity in the technician's vehicle
     * @param unit            unit of measure (e.g. EACH, METRES)
     */
    public record VehicleStockItem(
            UUID partId,
            String partCode,
            String description,
            int quantityOnHand,
            String unit
    ) {}
}
