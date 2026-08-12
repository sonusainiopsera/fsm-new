package com.fieldservice.inventory.application;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.PartRepository;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockBalanceRepository;
import com.fieldservice.domain.inventory.StockLocation;
import com.fieldservice.domain.inventory.StockLocationRepository;
import com.fieldservice.inventory.api.PartsAvailabilityQuery;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.PartRecord;
import com.fieldservice.inventory.api.StockQueryService;
import com.fieldservice.inventory.api.StockRecord;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class StockQueryServiceImpl implements StockQueryService {

    private static final SortAllowList PART_SORTS = SortAllowList.of(
            "partNumber", "partNumber",
            "description", "description",
            "unitOfMeasure", "unitOfMeasure",
            "active", "active",
            "createdAt", "createdAt"
    );

    private static final SortAllowList STOCK_SORTS = SortAllowList.of(
            "quantityOnHand", "quantityOnHand",
            "updatedAt", "updatedAt"
    );

    private final PartRepository partRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockLocationRepository stockLocationRepository;
    private final ScopedQueryExecutor scopedQueryExecutor;
    private final PartsAvailabilityLookupService lookupService;
    private final AvailabilityCacheGateway availabilityCache;
    private final PartsAvailabilityMetrics availabilityMetrics;

    StockQueryServiceImpl(
            PartRepository partRepository,
            StockBalanceRepository stockBalanceRepository,
            StockLocationRepository stockLocationRepository,
            ScopedQueryExecutor scopedQueryExecutor,
            PartsAvailabilityLookupService lookupService,
            AvailabilityCacheGateway availabilityCache,
            PartsAvailabilityMetrics availabilityMetrics) {
        this.partRepository = partRepository;
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockLocationRepository = stockLocationRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.lookupService = lookupService;
        this.availabilityCache = availabilityCache;
        this.availabilityMetrics = availabilityMetrics;
    }

    @Override
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public PartsAvailabilityResult batchCheckAvailability(PartsAvailabilityQuery query) {
        if (query.requiredParts().isEmpty()) {
            return PartsAvailabilityResult.empty();
        }
        String key = CacheKeyBuilder.build(query);
        try {
            return availabilityCache.get(key)
                    .map(cached -> {
                        availabilityMetrics.recordCacheHit();
                        return cached;
                    })
                    .orElseGet(() -> {
                        availabilityMetrics.recordCacheMiss();
                        PartsAvailabilityResult result = lookupService.lookup(query);
                        availabilityCache.put(key, result);
                        return result;
                    });
        } catch (Exception e) {
            availabilityMetrics.recordDegraded();
            return lookupService.lookup(query);
        }
    }

    @Override
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN', 'MANAGER')")
    public PagedResponse<PartRecord> listParts(PageQuery pageQuery, HttpServletRequest request) {
        Sort sort = buildPartSort(pageQuery);
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);

        Page<Part> page = partRepository.findAll(Specification.where(null), pageable);

        List<PartRecord> records = page.getContent().stream()
                .map(this::toPartRecord)
                .toList();

        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        PageLinks links = buildOffsetLinks(request, page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(records, meta, links);
    }

    @Override
    @PreAuthorize("hasAnyRole('DISPATCHER', 'TECHNICIAN', 'ADMIN', 'MANAGER')")
    public PagedResponse<StockRecord> listStock(
            @Nullable UUID locationId,
            @Nullable UUID partId,
            PageQuery pageQuery,
            HttpServletRequest request) {

        Specification<StockBalance> filter = buildStockFilter(locationId, partId);
        Sort sort = buildStockSort(pageQuery);
        PageRequest pageable = PageRequest.of(pageQuery.page(), pageQuery.size(), sort);

        Page<StockBalance> page = scopedQueryExecutor.findAll(
                StockBalance.class, filter, pageable, stockBalanceRepository);

        List<StockBalance> balances = page.getContent();

        // Batch-load parts and locations referenced by the result page
        Set<UUID> partIds = balances.stream().map(StockBalance::getPartId).collect(Collectors.toSet());
        Set<UUID> locationIds = balances.stream().map(StockBalance::getLocationId).collect(Collectors.toSet());

        Map<UUID, Part> partsById = partRepository.findAllById(partIds).stream()
                .collect(Collectors.toMap(Part::getId, p -> p));
        Map<UUID, StockLocation> locationsById = stockLocationRepository.findAllById(locationIds).stream()
                .collect(Collectors.toMap(StockLocation::getId, l -> l));

        List<StockRecord> records = balances.stream()
                .map(b -> toStockRecord(b, partsById, locationsById))
                .toList();

        PageMeta meta = PageMeta.of(page.getNumber(), page.getSize(), page.getTotalElements());
        PageLinks links = buildOffsetLinks(request, page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(records, meta, links);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private PartRecord toPartRecord(Part part) {
        return new PartRecord(
                part.getId(),
                part.getPartNumber(),
                part.getDescription(),
                part.getUnitOfMeasure(),
                part.getReorderPoint(),
                part.getReorderQuantity(),
                part.isActive()
        );
    }

    private StockRecord toStockRecord(
            StockBalance balance,
            Map<UUID, Part> partsById,
            Map<UUID, StockLocation> locationsById) {

        Part part = partsById.get(balance.getPartId());
        StockLocation location = locationsById.get(balance.getLocationId());

        return new StockRecord(
                balance.getPartId(),
                part != null ? part.getPartNumber() : null,
                balance.getLocationId(),
                location != null ? location.getLocationType() : null,
                balance.getQuantityOnHand(),
                part != null ? part.getReorderPoint() : 0,
                balance.getUpdatedAt()
        );
    }

    // -------------------------------------------------------------------------
    // Sort helpers
    // -------------------------------------------------------------------------

    private Sort buildPartSort(PageQuery pageQuery) {
        if (pageQuery.sort().isEmpty()) {
            return Sort.by(Sort.Direction.ASC, "partNumber", "id");
        }
        List<Sort.Order> orders = pageQuery.sort().stream()
                .map(sf -> {
                    String field = PART_SORTS.resolvePersistentName(sf.field());
                    return new Sort.Order(sf.direction(), field);
                })
                .collect(Collectors.toList());
        // Mandatory id tie-break
        boolean hasId = orders.stream().anyMatch(o -> "id".equals(o.getProperty()));
        if (!hasId) orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }

    private Sort buildStockSort(PageQuery pageQuery) {
        if (pageQuery.sort().isEmpty()) {
            return Sort.by(Sort.Direction.ASC, "id");
        }
        List<Sort.Order> orders = pageQuery.sort().stream()
                .map(sf -> {
                    String field = STOCK_SORTS.resolvePersistentName(sf.field());
                    return new Sort.Order(sf.direction(), field);
                })
                .collect(Collectors.toList());
        boolean hasId = orders.stream().anyMatch(o -> "id".equals(o.getProperty()));
        if (!hasId) orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }

    // -------------------------------------------------------------------------
    // Filter helpers
    // -------------------------------------------------------------------------

    private static Specification<StockBalance> buildStockFilter(
            @Nullable UUID locationId, @Nullable UUID partId) {
        Specification<StockBalance> spec = null;
        if (locationId != null) {
            Specification<StockBalance> locFilter =
                    (root, query, cb) -> cb.equal(root.get("locationId"), locationId);
            spec = spec == null ? locFilter : spec.and(locFilter);
        }
        if (partId != null) {
            Specification<StockBalance> partFilter =
                    (root, query, cb) -> cb.equal(root.get("partId"), partId);
            spec = spec == null ? partFilter : spec.and(partFilter);
        }
        return spec;
    }

    // -------------------------------------------------------------------------
    // Link helpers
    // -------------------------------------------------------------------------

    private static PageLinks buildOffsetLinks(
            HttpServletRequest request, int currentPage, int size, int totalPages) {
        String next = (currentPage + 1 < totalPages)
                ? replacePageParam(request, currentPage + 1, size)
                : null;
        String prev = (currentPage > 0)
                ? replacePageParam(request, currentPage - 1, size)
                : null;
        return PageLinks.of(next, prev);
    }

    private static String replacePageParam(HttpServletRequest request, int page, int size) {
        return UriComponentsBuilder.fromRequestUri(request)
                .replaceQueryParam("page", page)
                .replaceQueryParam("size", size)
                .toUriString();
    }
}
