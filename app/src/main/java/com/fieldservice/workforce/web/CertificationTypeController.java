package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.CertificationCurrencyService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin-manageable certification type registry.
 *
 * <p>GET is available to ADMIN, MANAGER, and DISPATCHER.
 * Mutating operations (POST, PUT, DELETE) are ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/certification-types")
public class CertificationTypeController {

    private final CertificationCurrencyService service;

    public CertificationTypeController(CertificationCurrencyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public ResponseEntity<PagedResponse<CertificationTypeResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {
        int capped = Math.min(Math.max(size, 1), 50);
        return ResponseEntity.ok(service.listCertificationTypes(
                PageRequest.of(page, capped, Sort.by("code"))));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CertificationTypeResponse> create(
            @Valid @RequestBody CertificationTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createCertificationType(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CertificationTypeResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CertificationTypeRequest request) {
        return ResponseEntity.ok(service.updateCertificationType(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CertificationTypeResponse> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(service.deactivateCertificationType(id));
    }
}
