package com.fieldservice.inventory.web;

import com.fieldservice.inventory.api.PartSummary;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.repository.PartRepository;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only endpoint for the parts catalogue.
 *
 * <p>CUSTOMER is explicitly denied. All other roles get the full active catalogue,
 * filtered by the AccessScope row-predicate from {@link PartScopeSpec}.
 * Page size is hard-capped at 50 by {@link PageQuery}.
 */
@RestController
@RequestMapping("/api/v1/inventory/parts")
public class InventoryPartsController {

    private static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "partNumber", "partNumber",
                    "name",       "name",
                    "createdAt",  "createdAt",
                    "active",     "active"
            ), "partNumber");

    private final PartRepository        partRepository;
    private final ScopedQueryExecutor   scopedQueryExecutor;
    private final RequestScopedAccessScope accessScope;

    public InventoryPartsController(PartRepository partRepository,
                                    ScopedQueryExecutor scopedQueryExecutor,
                                    RequestScopedAccessScope accessScope) {
        this.partRepository     = partRepository;
        this.scopedQueryExecutor = scopedQueryExecutor;
        this.accessScope        = accessScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'TECHNICIAN')")
    public ResponseEntity<PagedResponse<PartSummary>> listParts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String  sort) {

        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = query.toPageable(SORT_ALLOW_LIST);
        Page<Part> partPage = scopedQueryExecutor.findAll(
                partRepository, pageable, accessScope.get(), Part.class);

        List<PartSummary> data = partPage.getContent().stream()
                .map(PartSummary::from).toList();
        PageMeta meta = PageMeta.of(query.page(), query.size(), partPage.getTotalElements());

        PageLinks links = PageLinks.of(
                partPage.hasNext() ? "/api/v1/inventory/parts?page=" + (query.page() + 1) + "&size=" + query.size() : null,
                query.page() > 0  ? "/api/v1/inventory/parts?page=" + (query.page() - 1) + "&size=" + query.size() : null);

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }
}
