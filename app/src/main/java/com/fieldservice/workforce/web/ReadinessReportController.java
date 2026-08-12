package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.ReadinessReportService;
import com.fieldservice.workforce.web.dto.ReadinessRequirementRequest;
import com.fieldservice.workforce.web.dto.ReadinessRequirementResponse;
import com.fieldservice.workforce.web.dto.ReadinessSnapshotResponse;
import com.fieldservice.workforce.web.dto.ReadinessSummaryResponse;
import com.fieldservice.workforce.web.dto.TechnicianGapRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the certification data-readiness report (WO-122).
 *
 * <p>All report endpoints require MANAGER or ADMIN role; snapshot generation
 * and requirement CRUD require ADMIN only.
 *
 * <p>This report evidences the Phase 1 exit gate:
 * "certification data-readiness audit complete with a remediation plan".
 */
@RestController
@Tag(name = "Workforce - Readiness", description = "Certification data-readiness gate and report")
public class ReadinessReportController {

    private final ReadinessReportService service;

    public ReadinessReportController(ReadinessReportService service) {
        this.service = service;
    }

    // ── Report endpoints ─────────────────────────────────────────────────────

    @Operation(operationId = "getCertificationReadinessSummary",
               summary = "Aggregate certification readiness summary with gate verdict")
    @GetMapping("/api/v1/reports/certification-readiness")
    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public ResponseEntity<ReadinessSummaryResponse> getSummary() {
        return ResponseEntity.ok(service.getSummary());
    }

    @Operation(operationId = "getCertificationReadinessGaps",
               summary = "Paginated per-technician gap detail (max 50 per page)")
    @GetMapping("/api/v1/reports/certification-readiness/gaps")
    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public ResponseEntity<PagedResponse<TechnicianGapRecord>> getGaps(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.getGaps(page, size));
    }

    @Operation(operationId = "exportCertificationReadinessGapsCsv",
               summary = "CSV export of gap detail (MANAGER, ADMIN)")
    @GetMapping(value = "/api/v1/reports/certification-readiness/gaps.csv",
                produces = "text/csv")
    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public ResponseEntity<String> exportGapsCsv() {
        String csv = service.exportGapsCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"certification-readiness-gaps.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    @Operation(operationId = "getCertificationReadinessSnapshots",
               summary = "Paginated weekly snapshot trend")
    @GetMapping("/api/v1/reports/certification-readiness/snapshots")
    @PreAuthorize("hasAnyAuthority('MANAGER','ADMIN')")
    public ResponseEntity<PagedResponse<ReadinessSnapshotResponse>> getSnapshots(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.getSnapshots(page, size));
    }

    @Operation(operationId = "generateCertificationReadinessSnapshot",
               summary = "Generate (or regenerate) snapshot for current ISO week (ADMIN only)")
    @PostMapping("/api/v1/reports/certification-readiness/snapshots")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ReadinessSnapshotResponse> generateSnapshot() {
        return ResponseEntity.ok(service.generateSnapshot());
    }

    // ── Requirement CRUD (ADMIN) ──────────────────────────────────────────────

    @Operation(operationId = "listReadinessRequirements",
               summary = "List active readiness requirements (ADMIN)")
    @GetMapping("/api/v1/reports/certification-readiness/requirements")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<List<ReadinessRequirementResponse>> listRequirements() {
        return ResponseEntity.ok(service.listRequirements());
    }

    @Operation(operationId = "createReadinessRequirement",
               summary = "Create a new readiness requirement (ADMIN)")
    @PostMapping("/api/v1/reports/certification-readiness/requirements")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ReadinessRequirementResponse> createRequirement(
            @Valid @RequestBody ReadinessRequirementRequest body) {
        return ResponseEntity.ok(service.createRequirement(body));
    }

    @Operation(operationId = "updateReadinessRequirement",
               summary = "Update a readiness requirement (ADMIN)")
    @PutMapping("/api/v1/reports/certification-readiness/requirements/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ReadinessRequirementResponse> updateRequirement(
            @PathVariable UUID id,
            @Valid @RequestBody ReadinessRequirementRequest body) {
        return ResponseEntity.ok(service.updateRequirement(id, body));
    }

    @Operation(operationId = "deactivateReadinessRequirement",
               summary = "Deactivate a readiness requirement (ADMIN)")
    @DeleteMapping("/api/v1/reports/certification-readiness/requirements/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<Void> deactivateRequirement(@PathVariable UUID id) {
        service.deactivateRequirement(id);
        return ResponseEntity.noContent().build();
    }
}
