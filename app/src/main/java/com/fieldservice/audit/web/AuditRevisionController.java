package com.fieldservice.audit.web;

import com.fieldservice.audit.api.AuditExportService;
import com.fieldservice.audit.api.AuditRevisionQueryService;
import com.fieldservice.platform.exception.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * REST endpoints for audit revision search, detail diff, and export.
 *
 * <p>Access is restricted to ADMIN and COMPLIANCE_REVIEWER roles. All other roles receive 403
 * with no indication of whether the requested revision exists.
 *
 * <p>Entity type and sort field inputs are validated against the allow-list in
 * {@link com.fieldservice.audit.internal.AuditEntityMetadata}; non-allow-listed values
 * return 400 with field-level errors and no query execution.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-revisions")
@Tag(name = "Audit Revisions", description = "Immutable audit trail search and export")
@PreAuthorize("hasAnyAuthority('ADMIN', 'COMPLIANCE_REVIEWER')")
@Validated
public class AuditRevisionController {

    private final AuditRevisionQueryService queryService;
    private final AuditExportService exportService;

    public AuditRevisionController(AuditRevisionQueryService queryService,
                                    AuditExportService exportService) {
        this.queryService = queryService;
        this.exportService = exportService;
    }

    @Operation(operationId = "searchAuditRevisions",
               summary = "Search audit revisions with filtering and stable pagination")
    @GetMapping
    public ResponseEntity<AuditRevisionQueryService.RevisionPage> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer revFrom,
            @RequestParam(required = false) Integer revTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var filter = new AuditRevisionQueryService.RevisionFilter(
                entityType, entityId,
                actorId,
                from, to,
                revFrom, revTo);

        return ResponseEntity.ok(queryService.search(filter, page, size));
    }

    @Operation(operationId = "getAuditRevisionDiff",
               summary = "Get field-level before/after diff for a single revision")
    @GetMapping("/{revisionNumber}")
    public ResponseEntity<AuditRevisionQueryService.RevisionDetail> getRevision(
            @PathVariable int revisionNumber,
            @RequestParam String entityType,
            @RequestParam UUID entityId) {

        return queryService.getRevisionDiff(revisionNumber, entityType, entityId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Revision " + revisionNumber + " not found"));
    }

    @Operation(operationId = "requestAuditExport",
               summary = "Request a CSV or JSON export of filtered audit revisions")
    @PostMapping("/exports")
    public ResponseEntity<AuditExportService.ExportResult> requestExport(
            @RequestBody ExportRequest body,
            @AuthenticationPrincipal Jwt jwt) {

        UUID requestedBy = UUID.fromString(jwt.getSubject());
        var filter = new AuditRevisionQueryService.RevisionFilter(
                body.entityType(), body.entityId(), body.actorId(),
                body.from(), body.to(), body.revFrom(), body.revTo());

        AuditExportService.ExportResult result =
                exportService.requestExport(filter, body.format(), requestedBy);

        int statusCode = "PENDING".equals(result.status()) ? 202 : 200;
        return ResponseEntity.status(statusCode).body(result);
    }

    @Operation(operationId = "getAuditExportStatus",
               summary = "Get status of an asynchronous audit export")
    @GetMapping("/exports/{exportId}")
    public ResponseEntity<AuditExportService.ExportStatus> getExportStatus(
            @PathVariable UUID exportId) {
        return exportService.getExportStatus(exportId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Export " + exportId + " not found"));
    }

    public record ExportRequest(
            String entityType,
            UUID entityId,
            String actorId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Integer revFrom,
            Integer revTo,
            @Pattern(regexp = "CSV|JSON", message = "format must be CSV or JSON")
            String format
    ) {}
}
