package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.api.CertificationSummary;
import com.fieldservice.workforce.internal.CertificationCurrencyService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-technician certification endpoints and dispatch-facing eligibility query.
 *
 * <p>Certification currency is NEVER a stored flag — it is always computed from
 * {@code expires_on} at query time.
 */
@RestController
@RequestMapping("/api/v1/technicians")
public class CertificationController {

    private final CertificationCurrencyService service;

    public CertificationController(CertificationCurrencyService service) {
        this.service = service;
    }

    /**
     * Returns all certifications for the technician.
     *
     * <p>{@code current} and {@code daysUntilExpiry} in each row are derived at query time.
     * Defaults {@code atDate} to today when not supplied.
     */
    @GetMapping("/{id}/certifications")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER','TECHNICIAN')")
    public ResponseEntity<PagedResponse<CertificationSummary>> listCertifications(
            @PathVariable UUID id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate atDate,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate evalDate = atDate != null ? atDate : LocalDate.now();
        int capped = Math.min(Math.max(size, 1), 50);
        List<CertificationSummary> all = service.allCertifications(id, evalDate);
        int total  = all.size();
        int from   = Math.min(page * capped, total);
        int to     = Math.min(from + capped, total);
        List<CertificationSummary> data = all.subList(from, to);
        PageMeta meta = PageMeta.of(page, capped, total);
        String nextLink = to < total
                ? "/api/v1/technicians/" + id + "/certifications?page=" + (page + 1) + "&size=" + capped : null;
        String prevLink = page > 0
                ? "/api/v1/technicians/" + id + "/certifications?page=" + (page - 1) + "&size=" + capped : null;
        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    /**
     * Batch-upserts certifications for a technician (validate-all-then-persist semantics).
     * Supports {@code Idempotency-Key} header (handled by platform IdempotencyFilter).
     */
    @PutMapping("/{id}/certifications")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<List<CertificationRowResult>> upsertCertifications(
            @PathVariable UUID id,
            @Valid @RequestBody CertificationBatchRequest request) {
        List<CertificationRowResult> results = service.upsertCertifications(id, request.items());
        return ResponseEntity.ok(results);
    }

    /**
     * Dispatch-facing bulk eligibility query.
     *
     * <p>Returns the set of technician IDs that hold a current certification for
     * EVERY code in {@code requiredTypeCodes}, evaluated against {@code atDate}.
     * Implemented as one SQL round-trip (HAVING COUNT(DISTINCT ...)).
     */
    @GetMapping("/eligible")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','DISPATCHER')")
    public ResponseEntity<PagedResponse<UUID>> findEligible(
            @RequestParam Set<String> requiredTypeCodes,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate atDate,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDate evalDate = atDate != null ? atDate : LocalDate.now();
        int capped = Math.min(Math.max(size, 1), 50);
        Set<UUID> eligible = service.technicianIdsWithCurrentCertifications(requiredTypeCodes, evalDate);
        List<UUID> all     = List.copyOf(eligible);
        int total  = all.size();
        int from   = Math.min(page * capped, total);
        int to     = Math.min(from + capped, total);
        List<UUID> data = all.subList(from, to);
        PageMeta meta = PageMeta.of(page, capped, total);
        String nextLink = to < total
                ? "/api/v1/technicians/eligible?page=" + (page + 1) + "&size=" + capped : null;
        String prevLink = page > 0
                ? "/api/v1/technicians/eligible?page=" + (page - 1) + "&size=" + capped : null;
        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }
}
