package com.fieldservice.catalog.web;

import com.fieldservice.catalog.api.AssetRef;
import com.fieldservice.catalog.application.CatalogService;
import com.fieldservice.catalog.application.CreateAssetCommand;
import com.fieldservice.catalog.web.dto.CreateAssetRequest;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for asset resources.
 *
 * <p>Assets are nested under sites: {@code POST /api/v1/sites/{siteId}/assets}.
 * The {@code GET /api/v1/sites/{siteId}/assets} endpoint lists assets for a site.
 */
@RestController
@Tag(name = "Catalog - Assets", description = "Asset reference data")
public class AssetController {

    private final CatalogService catalogService;

    public AssetController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(operationId = "listAssetsForSite", summary = "List assets under a site")
    @GetMapping(value = "/api/v1/sites/{siteId}/assets",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<AssetRef>> listAssets(
            @PathVariable UUID siteId,
            @RequestParam(required = false) String q,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(catalogService.listAssetsForSite(siteId, q, pageQuery, request));
    }

    @Operation(operationId = "getAsset", summary = "Get a single asset by ID")
    @GetMapping(value = "/api/v1/assets/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetRef> getAsset(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getAsset(id));
    }

    @Operation(operationId = "createAsset", summary = "Create an asset under a site")
    @PostMapping(value = "/api/v1/sites/{siteId}/assets",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetRef> createAsset(
            @PathVariable UUID siteId,
            @Valid @RequestBody CreateAssetRequest body,
            UriComponentsBuilder ucb) {
        AssetRef created = catalogService.createAsset(siteId, toCommand(body));
        URI location = ucb.path("/api/v1/assets/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(operationId = "deactivateAsset", summary = "Deactivate an asset (soft delete)")
    @DeleteMapping(value = "/api/v1/assets/{id}")
    public ResponseEntity<Void> deactivateAsset(@PathVariable UUID id) {
        catalogService.deactivateAsset(id);
        return ResponseEntity.noContent().build();
    }

    private static CreateAssetCommand toCommand(CreateAssetRequest r) {
        return new CreateAssetCommand(r.assetTag(), r.manufacturer(), r.model(),
                r.serialNumber(), r.category(), r.installedOn());
    }
}
