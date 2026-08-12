package com.fieldservice.audit.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public interface for generating masked audit exports (CSV or JSON).
 *
 * <p>Small result sets (below the configured row ceiling) are generated synchronously
 * and the inline artefact is returned immediately. Large result sets are generated
 * asynchronously on the worker profile; callers receive a handle and poll for status.
 *
 * <p>Every export invocation — whether it succeeds, fails, or is queued — writes an
 * immutable administrative audit record capturing actor, filter parameters and row count.
 */
public interface AuditExportService {

    /**
     * Request an export for the given filter and format.
     *
     * @param request  export request parameters
     * @return synchronous result (with inline data) for small sets, or an async handle
     */
    ExportResult requestExport(ExportRequest request);

    /**
     * Poll the status of an asynchronous export.
     *
     * @param exportId  the export ID returned from {@link #requestExport}
     * @return current export status, or empty if no such export exists for the caller
     */
    Optional<ExportStatus> getStatus(UUID exportId, UUID requestedBy);

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────

    enum Format { CSV, JSON }

    record ExportRequest(
            AuditRevisionQueryService.RevisionFilter filter,
            Format   format,
            UUID     requestedBy) {}

    sealed interface ExportResult permits ExportResult.Synchronous, ExportResult.Async {

        record Synchronous(String content, Format format, int rowCount) implements ExportResult {}

        record Async(UUID exportId, String status) implements ExportResult {}
    }

    record ExportStatus(
            UUID    exportId,
            String  status,
            Integer rowCount,
            String  downloadReference,
            String  failureReason) {}
}
