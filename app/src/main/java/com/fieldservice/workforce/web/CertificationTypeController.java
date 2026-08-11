package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.CertificationCurrencyService;
import com.fieldservice.workforce.web.dto.CertificationTypeRequest;
import com.fieldservice.workforce.web.dto.CertificationTypeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * REST endpoints for the certification type catalogue.
 *
 * <p>Reads are permitted for ADMIN, MANAGER and DISPATCHER.
 * Writes (POST, PUT) are ADMIN-only — enforced server-side via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/certification-types")
@Tag(name = "Workforce - Certification Types", description = "Certification type catalogue management")
public class CertificationTypeController {

    static final int MAX_PAGE_SIZE = 50;

    private final CertificationCurrencyService service;

    public CertificationTypeController(CertificationCurrencyService service) {
        this.service = service;
    }

    @Operation(operationId = "listCertificationTypes", summary = "List certification types (paginated)")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','DISPATCHER')")
    public ResponseEntity<PagedResponse<CertificationTypeResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        Page<CertificationTypeResponse> result = service.listCertificationTypes(page, effectiveSize);
        PageMeta  meta  = PageMeta.of(page, effectiveSize, result.getTotalElements());
        PageLinks links = PageLinks.none();
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta, links));
    }

    @Operation(operationId = "createCertificationType", summary = "Create a certification type (ADMIN)")
    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<CertificationTypeResponse> create(
            @Valid @RequestBody CertificationTypeRequest req,
            UriComponentsBuilder ucb) {

        CertificationTypeResponse created = service.createCertificationType(req);
        var location = ucb.path("/api/v1/certification-types/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(operationId = "updateCertificationType", summary = "Update a certification type (ADMIN)")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<CertificationTypeResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CertificationTypeRequest req) {

        CertificationTypeResponse updated = service.updateCertificationType(id, req);
        return ResponseEntity.ok(updated);
    }
}
