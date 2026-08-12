package com.fieldservice.audit.api;

import com.fieldservice.platform.api.ApiErrorResponse;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.platform.api.FieldError;
import com.fieldservice.platform.util.UuidV7;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only endpoints for searching, inspecting, and exporting Envers audit revision history.
 *
 * <h3>RBAC</h3>
 * All endpoints require ADMIN or PRIVACY_ADMIN role (the compliance-reviewer role in this system).
 * DISPATCHER, MANAGER, TECHNICIAN and CUSTOMER callers receive 403 with no existence disclosure.
 *
 * <h3>Append-only invariant</h3>
 * Only GET and POST-to-export mappings exist. No PUT, PATCH or DELETE verbs are reachable from
 * this controller; no service or repository method capable of mutating audit history is called.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AuditRevisionController {

    private static final int MAX_PAGE_SIZE = 50;

    private final AuditRevisionQueryService queryService;
    private final AuditExportService        exportService;

    public AuditRevisionController(AuditRevisionQueryService queryService,
                                    AuditExportService exportService) {
        this.queryService  = queryService;
        this.exportService = exportService;
    }

    // ── Search ────────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/admin/audit-revisions
     *
     * <p>Paginated search over revision history. Stable ordering: (revisionTimestamp DESC, rev DESC).
     * Keyset cursor provided in response when more pages exist; pass as {@code cursor} parameter.
     */
    @GetMapping("/audit-revisions")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRIVACY_ADMIN')")
    public ResponseEntity<SearchEnvelope> search(
            @RequestParam(required = false)  String   entityType,
            @RequestParam(required = false)  UUID     entityId,
            @RequestParam(required = false)  String   actorId,
            @RequestParam(required = false)  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false)  Integer  revFrom,
            @RequestParam(required = false)  Integer  revTo,
            @RequestParam(required = false)  String   cursor,
            @RequestParam(defaultValue = "20") int    size) {

        AuditRevisionQueryService.RevisionFilter filter =
                new AuditRevisionQueryService.RevisionFilter(
                        entityType, entityId, actorId, from, to, revFrom, revTo);

        AuditRevisionQueryService.RevisionCursor keysetCursor = parseCursor(cursor);
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        AuditRevisionQueryService.RevisionPage page =
                queryService.search(filter, keysetCursor, clampedSize);

        String nextCursorStr = page.nextCursor() != null ? encodeCursor(page.nextCursor()) : null;
        return ResponseEntity.ok(new SearchEnvelope(page.data(), nextCursorStr, page.hasMore()));
    }

    // ── Detail ────────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/admin/audit-revisions/{revisionNumber}?entityType=&entityId=
     *
     * <p>Returns the full before/after field diff for a single revision.
     * 404 is returned for an unknown revision with no existence disclosure across scopes.
     */
    @GetMapping("/audit-revisions/{revisionNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRIVACY_ADMIN')")
    public ResponseEntity<?> getDetail(
            @PathVariable int     revisionNumber,
            @RequestParam  String entityType,
            @RequestParam  UUID   entityId) {

        Optional<AuditRevisionQueryService.RevisionDetail> detail =
                queryService.getDetail(revisionNumber, entityType, entityId);

        return detail
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(new DetailEnvelope(d)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiErrorResponse.of(ErrorCode.NOT_FOUND,
                                "Revision not found.", resolveTraceId())));
    }

    // ── Export ────────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/admin/audit-exports
     *
     * <p>Requests an export of the filtered revision set in CSV or JSON format.
     * Returns 200 with inline content for small result sets, or 202 with an export handle
     * for large sets that exceed the configured row ceiling.
     */
    @PostMapping("/audit-exports")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRIVACY_ADMIN')")
    public ResponseEntity<?> requestExport(
            @RequestBody ExportRequestDto body,
            Authentication authentication) {

        UUID actor = UUID.fromString(authentication.getName());
        AuditExportService.Format format;
        try {
            format = AuditExportService.Format.valueOf(body.format().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                            "Invalid format value.", List.of(new FieldError("format", body.format(),
                                    "Must be CSV or JSON")), resolveTraceId()));
        }

        AuditRevisionQueryService.RevisionFilter filter =
                new AuditRevisionQueryService.RevisionFilter(
                        body.entityType(), body.entityId(), body.actorId(),
                        body.from(), body.to(), body.revFrom(), body.revTo());

        AuditExportService.ExportRequest request =
                new AuditExportService.ExportRequest(filter, format, actor);
        AuditExportService.ExportResult result = exportService.requestExport(request);

        return switch (result) {
            case AuditExportService.ExportResult.Synchronous sync -> {
                String contentType = format == AuditExportService.Format.CSV
                        ? "text/csv" : MediaType.APPLICATION_JSON_VALUE;
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header("X-Export-Row-Count", String.valueOf(sync.rowCount()))
                        .body(sync.content());
            }
            case AuditExportService.ExportResult.Async async ->
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(new AsyncExportEnvelope(async.exportId(), async.status()));
        };
    }

    /**
     * GET /api/v1/admin/audit-exports/{exportId}
     *
     * <p>Polls the status of an asynchronous export. Returns 404 for unknown or
     * inaccessible exports (no existence disclosure across users).
     */
    @GetMapping("/audit-exports/{exportId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRIVACY_ADMIN')")
    public ResponseEntity<?> getExportStatus(
            @PathVariable UUID exportId,
            Authentication authentication) {

        UUID actor = UUID.fromString(authentication.getName());
        return exportService.getStatus(exportId, actor)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(new ExportStatusEnvelope(s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiErrorResponse.of(ErrorCode.NOT_FOUND,
                                "Export not found.", resolveTraceId())));
    }

    // ── Local exception handler ───────────────────────────────────────────────────────────

    @ExceptionHandler(AuditRevisionQueryService.InvalidAuditFilterException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidFilter(
            AuditRevisionQueryService.InvalidAuditFilterException ex) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.withFieldErrors(ErrorCode.VALIDATION_FAILED,
                        "Invalid audit filter.",
                        List.of(new FieldError(ex.getFieldName(), ex.getRejectedValue(),
                                "Value is not in the allow-listed set for " + ex.getFieldName())),
                        resolveTraceId()));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────

    record SearchEnvelope(
            List<AuditRevisionQueryService.RevisionSummary> data,
            String  nextCursor,
            boolean hasMore) {}

    record DetailEnvelope(AuditRevisionQueryService.RevisionDetail data) {}

    record AsyncExportEnvelope(UUID exportId, String status) {}

    record ExportStatusEnvelope(AuditExportService.ExportStatus data) {}

    record ExportRequestDto(
            String  entityType,
            UUID    entityId,
            String  actorId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Integer revFrom,
            Integer revTo,
            @NotBlank String format) {}

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private static AuditRevisionQueryService.RevisionCursor parseCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(encoded));
            String[] parts = decoded.split(":");
            return new AuditRevisionQueryService.RevisionCursor(
                    Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodeCursor(AuditRevisionQueryService.RevisionCursor cursor) {
        String raw = cursor.revTimestampMillis() + ":" + cursor.rev();
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    private static String resolveTraceId() {
        String id = MDC.get("traceId");
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }
}
