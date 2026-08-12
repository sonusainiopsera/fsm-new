package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditExportService;
import com.fieldservice.audit.api.AuditRevisionQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements synchronous and asynchronous audit export.
 *
 * <p>Result sets below {@link AuditExportProperties#getSyncExportRowCeiling()} are serialised
 * inline and returned immediately. Larger sets transition the export record to PENDING and
 * are generated asynchronously on the worker deployable.
 *
 * <p>Every export invocation (successful or failed) writes an administrative audit record via
 * {@link AuditExportRepository}.
 */
@Service
@EnableConfigurationProperties(AuditExportProperties.class)
public class AuditExportServiceImpl implements AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportServiceImpl.class);

    private final AuditRevisionRepository revisionRepository;
    private final AuditExportRepository exportRepository;
    private final PiiMaskingPolicy masking;
    private final AuditExportProperties props;

    public AuditExportServiceImpl(
            AuditRevisionRepository revisionRepository,
            AuditExportRepository exportRepository,
            PiiMaskingPolicy masking,
            AuditExportProperties props) {
        this.revisionRepository = revisionRepository;
        this.exportRepository = exportRepository;
        this.masking = masking;
        this.props = props;
    }

    @Override
    @Transactional
    public ExportResult requestExport(AuditRevisionQueryService.RevisionFilter filter,
                                       String format, UUID requestedBy) {
        Map<String, Object> filterJson = filterToMap(filter);
        AuditExportEntity exportRecord = new AuditExportEntity(requestedBy, filterJson, format, Instant.now());
        exportRepository.save(exportRecord);

        // Count rows to decide sync vs async
        String actorId = filter.actorUserId() != null ? filter.actorUserId().toString() : null;
        long totalRows = revisionRepository.count(
                filter.entityType(), filter.entityId(), actorId,
                filter.from(), filter.to(), filter.revFrom(), filter.revTo());

        if (totalRows > props.getSyncExportRowCeiling()) {
            log.info("audit.export.async exportId={} requestedBy={} estimatedRows={}", 
                    exportRecord.getId(), requestedBy, totalRows);
            return new ExportResult(exportRecord.getId(), false, null, "PENDING");
        }

        // Synchronous path
        try {
            List<AuditRevisionRepository.RevisionRow> rows = revisionRepository.search(
                    filter.entityType(), filter.entityId(), actorId,
                    filter.from(), filter.to(), filter.revFrom(), filter.revTo(),
                    0, props.getSyncExportRowCeiling());

            String content = "CSV".equals(format) ? toCsv(rows) : toJson(rows);
            exportRecord.markCompleted(rows.size(), null);
            exportRepository.save(exportRecord);

            log.info("audit.export.completed exportId={} requestedBy={} rows={}",
                    exportRecord.getId(), requestedBy, rows.size());
            return new ExportResult(exportRecord.getId(), true, content, "COMPLETED");
        } catch (Exception e) {
            exportRecord.markFailed(e.getMessage());
            exportRepository.save(exportRecord);
            log.error("audit.export.failed exportId={} error={}", exportRecord.getId(), e.getMessage(), e);
            return new ExportResult(exportRecord.getId(), false, null, "FAILED");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportStatus> getExportStatus(UUID exportId) {
        return exportRepository.findById(exportId)
                .map(e -> new ExportStatus(e.getId(), e.getStatus(), e.getRowCount(),
                        e.getArtefactReference(), null));
    }

    private String toCsv(List<AuditRevisionRepository.RevisionRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("revisionNumber,revisionTimestamp,actor,entityType,entityId,changeType\n");
        for (AuditRevisionRepository.RevisionRow r : rows) {
            sb.append(r.revisionNumber()).append(',')
              .append(r.revisionTimestamp()).append(',')
              .append(escapeCsv(r.actor())).append(',')
              .append(r.entityType()).append(',')
              .append(r.entityId()).append(',')
              .append(r.changeType()).append('\n');
        }
        return sb.toString();
    }

    private String toJson(List<AuditRevisionRepository.RevisionRow> rows) {
        return "[" + rows.stream().map(r ->
                "{\"revisionNumber\":" + r.revisionNumber() +
                ",\"revisionTimestamp\":\"" + r.revisionTimestamp() + "\"" +
                ",\"actor\":\"" + jsonEscape(r.actor()) + "\"" +
                ",\"entityType\":\"" + r.entityType() + "\"" +
                ",\"entityId\":\"" + r.entityId() + "\"" +
                ",\"changeType\":\"" + r.changeType() + "\"}")
                .collect(Collectors.joining(",")) + "]";
    }

    private static String escapeCsv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String jsonEscape(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, Object> filterToMap(AuditRevisionQueryService.RevisionFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f.entityType() != null) m.put("entityType", f.entityType());
        if (f.entityId() != null) m.put("entityId", f.entityId().toString());
        if (f.actorUserId() != null) m.put("actorUserId", f.actorUserId().toString());
        if (f.from() != null) m.put("from", f.from().toString());
        if (f.to() != null) m.put("to", f.to().toString());
        if (f.revFrom() != null) m.put("revFrom", f.revFrom());
        if (f.revTo() != null) m.put("revTo", f.revTo());
        return m;
    }
}
