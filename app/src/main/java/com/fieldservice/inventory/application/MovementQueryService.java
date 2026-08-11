package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockLedger;
import com.fieldservice.domain.inventory.StockLedgerRepository;
import com.fieldservice.inventory.api.MovementRecord;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Query service for the paginated stock ledger movement history API.
 *
 * <p>Access control:
 * <ul>
 *   <li>CUSTOMER — 403 (denied by {@code @PreAuthorize})</li>
 *   <li>TECHNICIAN — scoped to their vehicle location via {@link com.fieldservice.domain.inventory.StockLedgerScopePredicateProvider}</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — all movements</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class MovementQueryService {

    static final SortAllowList MOVEMENT_SORTS = SortAllowList.of(
            "occurredAt",    "occurredAt",
            "movementType",  "movementType",
            "deltaQuantity", "quantityDelta",
            "partId",        "partId"
    );

    private final StockLedgerRepository ledgerRepository;
    private final PartRepository partRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final AccessScopeResolver scopeResolver;

    public MovementQueryService(
            StockLedgerRepository ledgerRepository,
            PartRepository partRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            AccessScopeResolver scopeResolver) {
        this.ledgerRepository = ledgerRepository;
        this.partRepository = partRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.scopeResolver = scopeResolver;
    }

    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public PagedResponse<MovementRecord> listMovements(
            @Nullable UUID partId,
            @Nullable UUID locationId,
            @Nullable UUID workOrderId,
            @Nullable String movementType,
            @Nullable Instant from,
            @Nullable Instant to,
            PageQuery pageQuery,
            HttpServletRequest request) {

        // Belt-and-suspenders: CUSTOMER must not see ledger data even if @PreAuthorize is widened
        if (scopeResolver.resolve().isCustomer()) {
            throw new ScopedAccessDeniedException("CUSTOMER cannot access movement history");
        }

        Sort sort = buildSort(pageQuery);
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);
        Specification<StockLedger> spec = buildSpec(partId, locationId, workOrderId, movementType, from, to);

        Page<StockLedger> page = scopedQueryExecutor.findAll(StockLedger.class, spec, pageable, ledgerRepository);

        // Batch-resolve part numbers for display
        List<UUID> partIds = page.getContent().stream()
                .map(StockLedger::getPartId)
                .distinct()
                .toList();
        Map<UUID, String> partNumbers = partRepository.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, Part::getPartNumber));

        List<MovementRecord> records = page.getContent().stream()
                .map(e -> toRecord(e, partNumbers.get(e.getPartId())))
                .toList();

        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        PageLinks links = buildLinks(request, page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(records, meta, links);
    }

    private Sort buildSort(PageQuery pageQuery) {
        if (pageQuery.sort() == null || pageQuery.sort().isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (var sf : pageQuery.sort()) {
            String persistentName = MOVEMENT_SORTS.resolvePersistentName(sf.field());
            orders.add(new Sort.Order(sf.direction(), persistentName));
        }
        orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }

    private static Specification<StockLedger> buildSpec(
            @Nullable UUID partId, @Nullable UUID locationId, @Nullable UUID workOrderId,
            @Nullable String movementType, @Nullable Instant from, @Nullable Instant to) {

        Specification<StockLedger> spec = Specification.where(null);
        if (partId != null)        spec = spec.and((r, q, cb) -> cb.equal(r.get("partId"), partId));
        if (locationId != null)    spec = spec.and((r, q, cb) -> cb.equal(r.get("locationId"), locationId));
        if (workOrderId != null)   spec = spec.and((r, q, cb) -> cb.equal(r.get("workOrderId"), workOrderId));
        if (movementType != null)  spec = spec.and((r, q, cb) -> cb.equal(r.get("movementType"), movementType));
        if (from != null)          spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("occurredAt"), from));
        if (to != null)            spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("occurredAt"), to));
        return spec;
    }

    private static PageLinks buildLinks(HttpServletRequest request, int page, int size, int totalPages) {
        String next = (page + 1 < totalPages) ? replacePageParam(request, page + 1, size) : null;
        String prev = (page > 0)              ? replacePageParam(request, page - 1, size) : null;
        return PageLinks.of(next, prev);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequestUri(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }

    private static MovementRecord toRecord(StockLedger e, @Nullable String partNumber) {
        Instant occurred = e.getOccurredAt() != null ? e.getOccurredAt() : e.getCreatedAt();
        return new MovementRecord(
                e.getId(),
                e.getPartId(),
                partNumber,
                e.getFromLocationId() != null ? e.getFromLocationId() : e.getLocationId(),
                e.getToLocationId(),
                e.getMovementType(),
                e.getQuantityDelta(),
                e.getResultingQuantity(),
                e.getReferenceNo(),
                e.getWorkOrderId(),
                e.getActorUserId() != null ? e.getActorUserId() : e.getTechnicianId(),
                e.getCorrelationId(),
                occurred);
    }
}
