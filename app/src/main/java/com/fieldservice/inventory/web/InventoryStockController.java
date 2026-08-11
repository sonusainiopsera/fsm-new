package com.fieldservice.inventory.web;

import com.fieldservice.inventory.api.StockSummary;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.inventory.repository.StockLocationRepository;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only endpoint for stock balances.
 *
 * <p>CUSTOMER is explicitly denied. TECHNICIAN sees only balances for their vehicle
 * stock location. All other roles see all locations. Results can be filtered by
 * {@code locationId} or {@code partId} query parameters.
 *
 * <p>Scope is enforced by loading accessible stock location IDs via
 * {@link ScopedQueryExecutor} before applying the IN-list predicate on stock_balance.
 */
@RestController
@RequestMapping("/api/v1/inventory/stock")
public class InventoryStockController {

    private final StockBalanceRepository  stockBalanceRepository;
    private final StockLocationRepository stockLocationRepository;
    private final ScopedQueryExecutor     scopedQueryExecutor;
    private final RequestScopedAccessScope accessScope;

    public InventoryStockController(StockBalanceRepository stockBalanceRepository,
                                    StockLocationRepository stockLocationRepository,
                                    ScopedQueryExecutor scopedQueryExecutor,
                                    RequestScopedAccessScope accessScope) {
        this.stockBalanceRepository  = stockBalanceRepository;
        this.stockLocationRepository = stockLocationRepository;
        this.scopedQueryExecutor     = scopedQueryExecutor;
        this.accessScope             = accessScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<PagedResponse<StockSummary>> listStock(
            @RequestParam(required = false) UUID    locationId,
            @RequestParam(required = false) UUID    partId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        PageQuery query = PageQuery.of(page, size, null);
        Pageable pageable = Pageable.ofSize(query.size()).withPage(query.page());

        // Resolve accessible location IDs via the scoped location repository
        Page<StockLocation> locationPage = scopedQueryExecutor.findAll(
                stockLocationRepository,
                Pageable.unpaged(Sort.by("id")),
                accessScope.get(),
                StockLocation.class);
        List<UUID> accessibleLocationIds = locationPage.getContent().stream()
                .map(StockLocation::getId).toList();

        if (accessibleLocationIds.isEmpty()) {
            return ResponseEntity.ok(PagedResponse.empty(query.size()));
        }

        Specification<StockBalance> spec = buildSpec(accessibleLocationIds, locationId, partId);
        Page<StockBalance> balancePage = stockBalanceRepository.findAll(spec, pageable);

        List<StockSummary> data = balancePage.getContent().stream()
                .map(StockSummary::from).toList();
        PageMeta meta = PageMeta.of(query.page(), query.size(), balancePage.getTotalElements());
        PageLinks links = PageLinks.of(
                balancePage.hasNext() ? "/api/v1/inventory/stock?page=" + (query.page() + 1) + "&size=" + query.size() : null,
                query.page() > 0      ? "/api/v1/inventory/stock?page=" + (query.page() - 1) + "&size=" + query.size() : null);

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    private static Specification<StockBalance> buildSpec(
            List<UUID> accessibleLocationIds, UUID locationIdFilter, UUID partIdFilter) {

        Specification<StockBalance> spec =
                (root, query, cb) -> root.get("locationId").in(accessibleLocationIds);

        if (locationIdFilter != null) {
            final UUID locId = locationIdFilter;
            spec = spec.and((root, query, cb) -> cb.equal(root.get("locationId"), locId));
        }
        if (partIdFilter != null) {
            final UUID pId = partIdFilter;
            spec = spec.and((root, query, cb) -> cb.equal(root.get("partId"), pId));
        }
        return spec;
    }
}
