package com.fieldservice.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.audit.api.AuditExportService;
import com.fieldservice.audit.api.AuditRevisionQueryService;
import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionFilter;
import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionSummary;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link AuditExportService}.
 *
 * <p>Below the configured row ceiling: generates the export synchronously, masks PII,
 * and returns the inline artefact. Above the ceiling: saves an {@code audit_export}
 * record as QUEUED and returns an async handle (actual generation is deferred to the
 * worker profile scheduled job, out of scope here).
 *
 * <p>Every export invocation — success or failure — writes an immutable administrative
 * audit record via structured log with actor, filter parameters and row count.
 */
@Service
class AuditExportServiceImpl implements AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportServiceImpl.class);

    private final AuditRevisionRepository repository;
    private final AuditExportRepository   exportRepository;
    private final AuditProperties         properties;
    private final ObjectMapper            objectMapper;

    AuditExportServiceImpl(AuditRevisionRepository repository,
                            AuditExportRepository exportRepository,
                            AuditProperties properties,
                            ObjectMapper objectMapper) {
        this.repository       = repository;
        this.exportRepository = exportRepository;
        this.properties       = properties;
        this.objectMapper     = objectMapper;
    }

    @Override
    @Transactional
    public ExportResult requestExport(ExportRequest request) {
        RevisionFilter filter  = request.filter();
        Format         format  = request.format();
        UUID           actor   = request.requestedBy();

        long count = repository.countSearch(filter);

        // Write administrative audit record regardless of path taken.
        log.info("audit_export_requested actor={} format={} filter={} estimatedRows={}",
                actor, format, safeFilterJson(filter), count);

        if (count > properties.exportRowCeiling()) {
            // Async path: queue a job record and return a handle.
            UUID exportId = UuidV7.generate();
            AuditExportEntity entity = AuditExportEntity.create(
                    exportId, actor, safeFilterJson(filter), format.name(), Instant.now());
            exportRepository.save(entity);

            log.info("audit_export_queued exportId={} actor={} estimatedRows={}",
                    exportId, actor, count);
            return new ExportResult.Async(exportId, "QUEUED");
        }

        // Synchronous path: generate inline.
        try {
            List<RevisionSummary> rows = repository.searchForExport(filter, properties.exportRowCeiling());
            String content = format == Format.CSV
                    ? toCsv(rows, filter.entityType())
                    : toJson(rows, filter.entityType());

            log.info("audit_export_completed actor={} format={} rowCount={}", actor, format, rows.size());
            return new ExportResult.Synchronous(content, format, rows.size());

        } catch (Exception ex) {
            log.warn("audit_export_failed actor={} format={} reason={}", actor, format, ex.getMessage());
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportStatus> getStatus(UUID exportId, UUID requestedBy) {
        return exportRepository.findByIdAndRequestedBy(exportId, requestedBy)
                .map(e -> new ExportStatus(
                        e.getId(),
                        e.getStatus(),
                        e.getRowCount(),
                        e.getArtefactReference(),
                        e.getFailureReason()));
    }

    // ── Private ───────────────────────────────────────────────────────────────────────────

    private String toCsv(List<RevisionSummary> rows, String entityType) {
        StringBuilder sb = new StringBuilder();
        sb.append("revisionNumber,revisionTimestamp,actorUserId,actorRole,entityType,entityId,changeType\n");
        for (RevisionSummary row : rows) {
            sb.append(row.revisionNumber()).append(',')
              .append(row.revisionTimestamp()).append(',')
              .append(mask(entityType, "actorUserId", safeStr(row.actorUserId()))).append(',')
              .append(safeStr(row.actorRole())).append(',')
              .append(safeStr(row.entityType())).append(',')
              .append(row.entityId() != null ? row.entityId() : "").append(',')
              .append(safeStr(row.changeType())).append('\n');
        }
        return sb.toString();
    }

    private String toJson(List<RevisionSummary> rows, String entityType) {
        List<Object> masked = rows.stream().map(r -> new java.util.LinkedHashMap<String, Object>() {{
            put("revisionNumber",     r.revisionNumber());
            put("revisionTimestamp",  r.revisionTimestamp());
            put("actorUserId",        mask(entityType, "actorUserId", safeStr(r.actorUserId())));
            put("actorRole",          r.actorRole());
            put("entityType",         r.entityType());
            put("entityId",           r.entityId());
            put("changeType",         r.changeType());
        }}).collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Export JSON serialisation failed", e);
        }
    }

    private String mask(String entityType, String field, String value) {
        return PiiMaskingPolicy.mask(entityType, field, value);
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private String safeFilterJson(RevisionFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
