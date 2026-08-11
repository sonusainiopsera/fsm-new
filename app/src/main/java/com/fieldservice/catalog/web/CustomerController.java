package com.fieldservice.catalog.web;

import com.fieldservice.catalog.CatalogService;
import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.security.RequestScopedAccessScope;
import com.fieldservice.site.domain.Site;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private static final SortAllowList CUSTOMER_SORT = SortAllowList.of(Map.of(
            "accountCode", "accountCode",
            "legalName",   "legalName",
            "active",      "active",
            "createdAt",   "createdAt"
    ), "legalName");

    private static final SortAllowList SITE_SORT = SortAllowList.of(Map.of(
            "siteCode",    "siteCode",
            "displayName", "displayName",
            "active",      "active",
            "createdAt",   "createdAt"
    ), "displayName");

    private final CatalogService           service;
    private final RequestScopedAccessScope accessScope;

    public CustomerController(CatalogService service, RequestScopedAccessScope accessScope) {
        this.service     = service;
        this.accessScope = accessScope;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    public ResponseEntity<PagedResponse<CustomerResponse>> listCustomers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String  sort,
            @RequestParam(required = false) String  q) {

        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = query.toPageable(CUSTOMER_SORT);

        Specification<CustomerAccount> filter = buildCustomerFilter(q);
        Page<CustomerAccount> result = service.findCustomers(filter, pageable, accessScope.get());

        List<CustomerResponse> data = result.getContent().stream()
                .map(CustomerResponse::from).toList();
        PageMeta  meta  = PageMeta.of(query.page(), query.size(), result.getTotalElements());
        PageLinks links = PageLinks.of(
                result.hasNext() ? "/api/v1/customers?page=" + (query.page() + 1) + "&size=" + query.size() : null,
                query.page() > 0 ? "/api/v1/customers?page=" + (query.page() - 1) + "&size=" + query.size() : null);

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER')")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable UUID id) {
        return service.findCustomer(id, accessScope.get())
                .map(c -> ResponseEntity.ok(CustomerResponse.from(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody @Valid CustomerRequest request) {
        CustomerAccount created = service.createCustomer(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(CustomerResponse.from(created));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deactivateCustomer(@PathVariable UUID id) {
        service.deactivateCustomer(id, accessScope.get());
        return ResponseEntity.noContent().build();
    }

    // ---- Sites nested under customer ----------------------------------------

    @GetMapping("/{customerId}/sites")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    public ResponseEntity<PagedResponse<SiteResponse>> listSites(
            @PathVariable UUID customerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String  sort) {

        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = query.toPageable(SITE_SORT);

        Page<Site> result = service.findSitesByCustomer(customerId, null, pageable, accessScope.get());

        List<SiteResponse> data = result.getContent().stream().map(SiteResponse::from).toList();
        PageMeta  meta  = PageMeta.of(query.page(), query.size(), result.getTotalElements());
        PageLinks links = PageLinks.of(
                result.hasNext() ? "/api/v1/customers/" + customerId + "/sites?page=" + (query.page() + 1) + "&size=" + query.size() : null,
                query.page() > 0 ? "/api/v1/customers/" + customerId + "/sites?page=" + (query.page() - 1) + "&size=" + query.size() : null);

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    @PostMapping("/{customerId}/sites")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<SiteResponse> createSite(
            @PathVariable UUID customerId,
            @RequestBody @Valid SiteRequest request) {

        Site created = service.createSite(customerId, request, accessScope.get());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/sites/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(SiteResponse.from(created));
    }

    // ---- Filter builders ----------------------------------------------------

    private Specification<CustomerAccount> buildCustomerFilter(String q) {
        if (q == null || q.isBlank()) return null;
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("legalName")), pattern),
                cb.like(cb.lower(root.get("accountCode")), pattern)
        );
    }
}
