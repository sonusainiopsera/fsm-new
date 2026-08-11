package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.api.CertificationRef;
import com.fieldservice.workforce.internal.CertificationCurrencyService;
import com.fieldservice.workforce.internal.TechnicianCertificationEntity;
import com.fieldservice.workforce.web.dto.CertificationBatchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST endpoints for per-technician certification records.
 *
 * <p>RBAC:
 * <ul>
 *   <li>ADMIN, MANAGER — full read + write</li>
 *   <li>DISPATCHER — read all technicians</li>
 *   <li>TECHNICIAN — read only own certifications</li>
 *   <li>CUSTOMER — no access (403)</li>
 * </ul>
 *
 * <p>Currency is derived at the response mapping layer — never stored.
 */
@RestController
@RequestMapping("/api/v1/technicians")
@Tag(name = "Workforce - Certifications", description = "Technician certification records")
public class CertificationController {

    private final CertificationCurrencyService service;

    public CertificationController(CertificationCurrencyService service) {
        this.service = service;
    }

    @Operation(operationId = "getTechnicianCertifications",
               summary = "Get certifications for a technician at a given date")
    @GetMapping("/{id}/certifications")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','DISPATCHER') or " +
                  "(hasAuthority('TECHNICIAN') and #jwt.claims['technicianId'] == #id.toString())")
    public ResponseEntity<PagedResponse<CertificationRef>> getCertifications(
            @PathVariable UUID id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate,
            @AuthenticationPrincipal Jwt jwt) {

        LocalDate evaluationDate = atDate != null ? atDate : LocalDate.now();
        List<CertificationRef> refs = service.currentCertifications(id, evaluationDate);
        PageMeta  meta  = PageMeta.of(0, refs.size(), (long) refs.size());
        PageLinks links = PageLinks.none();
        return ResponseEntity.ok(PagedResponse.of(refs, meta, links));
    }

    @Operation(operationId = "upsertTechnicianCertifications",
               summary = "Batch upsert certifications for a technician (ADMIN, MANAGER)")
    @PutMapping("/{id}/certifications")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public ResponseEntity<List<CertificationRef>> upsertCertifications(
            @PathVariable UUID id,
            @Valid @RequestBody CertificationBatchRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        LocalDate today = LocalDate.now();
        List<TechnicianCertificationEntity> saved =
                service.upsertCertifications(id, body.items(), today);
        List<CertificationRef> refs = saved.stream()
                .map(tc -> service.toRef(tc, today))
                .collect(Collectors.toList());
        return ResponseEntity.ok(refs);
    }

    @Operation(operationId = "getEligibleTechnicians",
               summary = "Get technician IDs eligible by current certifications at a date")
    @GetMapping("/eligible")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','DISPATCHER')")
    public ResponseEntity<PagedResponse<UUID>> getEligible(
            @RequestParam Set<String> requiredTypeCodes,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate atDate) {

        LocalDate evaluationDate = atDate != null ? atDate : LocalDate.now();
        Set<UUID> eligible = service.technicianIdsWithCurrentCertifications(
                requiredTypeCodes, evaluationDate);
        List<UUID> sorted = eligible.stream().sorted().collect(Collectors.toList());
        PageMeta  meta  = PageMeta.of(0, sorted.size(), (long) sorted.size());
        PageLinks links = PageLinks.none();
        return ResponseEntity.ok(PagedResponse.of(sorted, meta, links));
    }
}
