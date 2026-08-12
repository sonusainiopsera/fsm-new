package com.fieldservice.audit.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public interface for requesting audit trail exports.
 *
 * <p>Small result sets (below the configured row ceiling) are returned inline.
 * Large result sets are generated asynchronously and polled via the returned export id.
 */
public interface AuditExportService {

    ExportResult requestExport(AuditRevisionQueryService.RevisionFilter filter,
                                String format, UUID requestedBy);

    Optional<ExportStatus> getExportStatus(UUID exportId);

    record ExportResult(UUID exportId, boolean immediate, String content, String status) {}

    record ExportStatus(UUID exportId, String status, Integer rowCount,
                        String artefactReference, String failureReason) {}
}
