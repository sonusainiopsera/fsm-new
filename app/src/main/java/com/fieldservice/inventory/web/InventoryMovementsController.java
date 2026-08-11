package com.fieldservice.inventory.web;

import com.fieldservice.inventory.domain.StockLedger;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.inventory.ledger.StockLedgerRepository;
import com.fieldservice.inventory.repository.StockLocationRepository;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only paginated movement history endpoint.
 *
 * <p>Access rules:
 * <ul>
 *   <li>CUSTOMER — 403 with no existence disclosure (deny by default).</li>
 *   <li>TECHNICIAN — scoped to movements from their own vehicle stock location.</li>
 *   <li>DISPATCHER, MANAGER, ADMIN — see all movements.</li>
 * </ul>
 *
 * <p>Max page size: 50. Sort allow-list: occurredAt, deltaQuantity (default: occurredAt DESC, id DESC).
 */
@RestController
@RequestMapping("/api/v1/inventory/movements")
public class InventoryMovementsController {

    private static final Set<String> SORT_ALLOW_LIST = Set.of("occurredAt", "deltaQuantity", "id");

    private final StockLedgerRepository    ledgerRepository;
    private final StockLocationRepository  locationRepository;
    private final ScopedQueryExecutor      scopedQueryExecutor;
    private final RequestScopedAccessScope accessScope;

    public InventoryMovementsController(StockLedgerRepository ledgerRepository,
                                         StockLocationRepository locationRepository,
                                         ScopedQueryExecutor scopedQueryExecutor,
                                         RequestScopedAccessScope accessScope) {
        this.ledgerRepository   = ledgerRepository;
        this.locationRepository = locationRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.accessScope         = accessScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<PagedResponse<MovementResponse>> listMovements(
            @RequestParam(required = false) UUID    partId,
            @RequestParam(required = false) UUID    locationId,
            @RequestParam(required = false) UUID    workOrderId,
            @RequestParam(required = false) String  movementType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String sort) {

        int cappedSize = Math.min(Math.max(size, 1), 50);
        AccessScope scope = accessScope.get();

        // Technician scope: restrict to their own vehicle location(s)
        Set<UUID> allowedLocationIds = null;
        if (scope.roles().contains("TECHNICIAN") && scope.technicianId() != null) {
            allowedLocationIds = locationRepository.findAllByTechnicianId(scope.technicianId())
                    .stream().map(StockLocation::getId).collect(Collectors.toSet());
        }
        final Set<UUID> technicianLocations = allowedLocationIds;

        // Build filter specification (all predicates applied before pagination)
        Specification<StockLedger> spec = buildSpec(
                partId, locationId, workOrderId, movementType, from, to, technicianLocations);

        Sort resolvedSort = resolveSort(sort);
        Pageable pageable = PageRequest.of(page, cappedSize, resolvedSort);

        Page<StockLedger> resultPage = ledgerRepository.findAll(spec, pageable);

        List<MovementResponse> data = resultPage.getContent().stream()
                .map(MovementResponse::from).toList();

        PageMeta meta = PageMeta.of(page, cappedSize, resultPage.getTotalElements());
        String nextLink = resultPage.hasNext()
                ? buildLink(page + 1, cappedSize, sort, partId, locationId, workOrderId, movementType, from, to)
                : null;
        String prevLink = page > 0
                ? buildLink(page - 1, cappedSize, sort, partId, locationId, workOrderId, movementType, from, to)
                : null;

        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    private Specification<StockLedger> buildSpec(UUID partId, UUID locationId, UUID workOrderId,
                                                  String movementType, Instant from, Instant to,
                                                  Set<UUID> technicianLocations) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (partId != null) {
                predicates.add(cb.equal(root.get("partId"), partId));
            }
            if (locationId != null) {
                predicates.add(cb.equal(root.get("fromLocationId"), locationId));
            }
            if (workOrderId != null) {
                predicates.add(cb.equal(root.get("workOrderId"), workOrderId));
            }
            if (movementType != null && !movementType.isBlank()) {
                predicates.add(cb.equal(root.get("movementType"), movementType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), to));
            }
            // Technician scope: restrict to their vehicle location(s)
            if (technicianLocations != null && !technicianLocations.isEmpty()) {
                predicates.add(root.get("fromLocationId").in(technicianLocations));
            } else if (technicianLocations != null) {
                // Technician with no vehicle location — return nothing
                predicates.add(cb.disjunction());
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Sort resolveSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        String[] parts = sortParam.split(":");
        String field = parts[0];
        if (!SORT_ALLOW_LIST.contains(field)) {
            return Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1]))
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private static String buildLink(int pg, int sz, String sort, UUID partId, UUID locationId,
                                    UUID workOrderId, String movementType, Instant from, Instant to) {
        StringBuilder sb = new StringBuilder("/api/v1/inventory/movements?page=")
                .append(pg).append("&size=").append(sz);
        if (sort != null)        sb.append("&sort=").append(sort);
        if (partId != null)      sb.append("&partId=").append(partId);
        if (locationId != null)  sb.append("&locationId=").append(locationId);
        if (workOrderId != null) sb.append("&workOrderId=").append(workOrderId);
        if (movementType != null) sb.append("&movementType=").append(movementType);
        if (from != null)        sb.append("&from=").append(from);
        if (to != null)          sb.append("&to=").append(to);
        return sb.toString();
    }
}
