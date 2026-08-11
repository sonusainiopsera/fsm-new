package com.fieldservice.catalog.web;

import com.fieldservice.catalog.api.SiteRef;
import com.fieldservice.catalog.application.CatalogService;
import com.fieldservice.catalog.application.CreateSiteCommand;
import com.fieldservice.catalog.web.dto.CreateSiteRequest;
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
 * REST controller for site resources.
 *
 * <p>Sites are nested under customers: {@code GET /api/v1/customers/{customerId}/sites}.
 * The {@code /api/v1/sites/{id}} path is also provided for direct access by technicians
 * and customers who already know the site ID.
 */
@RestController
@Tag(name = "Catalog - Sites", description = "Site reference data")
public class SiteController {

    private final CatalogService catalogService;

    public SiteController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(operationId = "listSitesForCustomer", summary = "List sites under a customer")
    @GetMapping(value = "/api/v1/customers/{customerId}/sites",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<SiteRef>> listSites(
            @PathVariable UUID customerId,
            @RequestParam(required = false) String q,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(catalogService.listSitesForCustomer(customerId, q, pageQuery, request));
    }

    @Operation(operationId = "getSite", summary = "Get a single site by ID")
    @GetMapping(value = "/api/v1/sites/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SiteRef> getSite(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getSite(id));
    }

    @Operation(operationId = "createSite", summary = "Create a site under a customer")
    @PostMapping(value = "/api/v1/customers/{customerId}/sites",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SiteRef> createSite(
            @PathVariable UUID customerId,
            @Valid @RequestBody CreateSiteRequest body,
            UriComponentsBuilder ucb) {
        SiteRef created = catalogService.createSite(customerId, toCommand(body));
        URI location = ucb.path("/api/v1/sites/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(operationId = "deactivateSite", summary = "Deactivate a site (soft delete)")
    @DeleteMapping(value = "/api/v1/sites/{id}")
    public ResponseEntity<Void> deactivateSite(@PathVariable UUID id) {
        catalogService.deactivateSite(id);
        return ResponseEntity.noContent().build();
    }

    private static CreateSiteCommand toCommand(CreateSiteRequest r) {
        return new CreateSiteCommand(r.siteCode(), r.displayName(), r.address(),
                r.postcode(), r.latitude(), r.longitude(), r.accessNotes());
    }
}
