package com.fieldservice.catalog.web;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.catalog.CatalogService;
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
@RequestMapping("/api/v1/sites")
public class SiteController {

    private static final SortAllowList ASSET_SORT = SortAllowList.of(Map.of(
            "assetTag",    "assetTag",
            "manufacturer","manufacturer",
            "model",       "model",
            "category",    "category",
            "active",      "active",
            "createdAt",   "createdAt"
    ), "assetTag");

    private final CatalogService           service;
    private final RequestScopedAccessScope accessScope;

    public SiteController(CatalogService service, RequestScopedAccessScope accessScope) {
        this.service     = service;
        this.accessScope = accessScope;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    public ResponseEntity<SiteResponse> getSite(@PathVariable UUID id) {
        return service.findSite(id, accessScope.get())
                .map(s -> ResponseEntity.ok(SiteResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deactivateSite(@PathVariable UUID id) {
        service.deactivateSite(id, accessScope.get());
        return ResponseEntity.noContent().build();
    }

    // ---- Assets nested under site -------------------------------------------

    @GetMapping("/{siteId}/assets")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER', 'CUSTOMER', 'TECHNICIAN')")
    public ResponseEntity<PagedResponse<AssetResponse>> listAssets(
            @PathVariable UUID siteId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String  sort) {

        PageQuery query = PageQuery.of(page, size, sort);
        Pageable pageable = query.toPageable(ASSET_SORT);

        Page<Asset> result = service.findAssetsBySite(siteId, null, pageable, accessScope.get());

        List<AssetResponse> data = result.getContent().stream().map(AssetResponse::from).toList();
        PageMeta  meta  = PageMeta.of(query.page(), query.size(), result.getTotalElements());
        PageLinks links = PageLinks.of(
                result.hasNext() ? "/api/v1/sites/" + siteId + "/assets?page=" + (query.page() + 1) + "&size=" + query.size() : null,
                query.page() > 0 ? "/api/v1/sites/" + siteId + "/assets?page=" + (query.page() - 1) + "&size=" + query.size() : null);

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    @PostMapping("/{siteId}/assets")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<AssetResponse> createAsset(
            @PathVariable UUID siteId,
            @RequestBody @Valid AssetRequest request) {

        Asset created = service.createAsset(siteId, request, accessScope.get());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/assets/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(AssetResponse.from(created));
    }

    @DeleteMapping("/{siteId}/assets/{assetId}")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deactivateAsset(
            @PathVariable UUID siteId,
            @PathVariable UUID assetId) {
        service.deactivateAsset(assetId, accessScope.get());
        return ResponseEntity.noContent().build();
    }
}
