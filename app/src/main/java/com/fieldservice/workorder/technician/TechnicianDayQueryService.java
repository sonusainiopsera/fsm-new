package com.fieldservice.workorder.technician;

import com.fieldservice.platform.api.exception.InvalidSortException;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only query service for the technician today's-jobs endpoint.
 *
 * <p>Enforces row-scope as a SQL predicate: {@code assigned_technician_id = :technicianId}
 * is always present in the WHERE clause — out-of-scope rows are never loaded from the
 * database.
 *
 * <p>Day window logic:
 * <ul>
 *   <li>Jobs with {@code scheduled_window_start} in {@code [dayStart, dayEnd)} are included.</li>
 *   <li>Open jobs in ASSIGNED, EN_ROUTE, IN_PROGRESS or ON_HOLD with an earlier or absent
 *       scheduled window are included as carry-overs.</li>
 * </ul>
 *
 * <p>ETag is derived from {@code MAX(version)} + {@code COUNT(*)} + the requested date
 * string so it changes whenever any job is updated, added, or removed from the set.
 */
@Service
public class TechnicianDayQueryService {

    private static final Logger log = LoggerFactory.getLogger(TechnicianDayQueryService.class);

    static final int MAX_PAGE_SIZE = 50;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("scheduledStart", "priority");

    private static final String PRIORITY_CASE =
            "CASE wo.priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 " +
            "WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END";

    private final JdbcTemplate jdbc;

    public TechnicianDayQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the technician's day jobs with pagination and a pre-computed ETag.
     *
     * @param technicianId  technician UUID from the JWT — never from client input
     * @param date          operating day (defaults to today in UTC if null)
     * @param page          0-based page number
     * @param size          page size, server-capped at {@value #MAX_PAGE_SIZE}
     * @param sortParam     optional sort field: {@code scheduledStart} or {@code priority}
     * @return paged jobs for the day, plus computed ETag string
     */
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'ADMIN')")
    @Transactional(readOnly = true)
    public TechnicianDayResult findDayJobs(UUID technicianId, LocalDate date,
                                            int page, int size, String sortParam) {
        if (technicianId == null) {
            return TechnicianDayResult.empty(size);
        }

        int cappedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int offset     = Math.max(0, page) * cappedSize;
        LocalDate effectiveDate = (date != null) ? date : LocalDate.now(ZoneOffset.UTC);

        Instant dayStart = effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd   = effectiveDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        String orderClause = buildOrderClause(sortParam);

        // --- Count query (also yields MAX version for ETag) ---
        String meta = buildMetaQuery();
        Object[] metaParams = {technicianId, dayStart, dayEnd, dayStart};
        var metaRow = jdbc.queryForMap(meta, metaParams);
        long totalElements = toLong(metaRow.get("cnt"));
        long maxVersion    = toLong(metaRow.get("max_ver"));

        // --- ETag computation ---
        String etag = computeEtag(technicianId, effectiveDate, totalElements, maxVersion);

        // --- Data query ---
        String dataQuery = buildDataQuery(orderClause);
        Object[] dataParams = {technicianId, dayStart, dayEnd, dayStart, cappedSize, offset};
        List<TechnicianJobSummary> rows = jdbc.query(dataQuery, dataParams, (rs, rowNum) -> {
            String rawPhone = rs.getString("contact_phone");
            return new TechnicianJobSummary(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("reference"),
                    rs.getString("priority"),
                    rs.getString("state"),
                    toInstant(rs.getTimestamp("scheduled_window_start")),
                    toInstant(rs.getTimestamp("scheduled_window_end")),
                    rs.getString("site_name"),
                    rs.getString("site_address"),
                    rs.getBigDecimal("latitude"),
                    rs.getBigDecimal("longitude"),
                    rs.getString("asset_tag"),
                    rs.getString("asset_description"),
                    rs.getString("fault_summary"),
                    toInstant(rs.getTimestamp("resolution_deadline")),
                    rs.getBoolean("sla_at_risk"),
                    ContactMasker.maskPhone(rawPhone));
        });

        log.info("technician_day_query technicianId={} date={} count={} traceId={}",
                technicianId, effectiveDate, totalElements, MDC.get("traceId"));

        int totalPages = cappedSize > 0 ? (int) Math.ceil((double) totalElements / cappedSize) : 0;
        PageMeta pageMeta = new PageMeta(page, cappedSize, totalElements, totalPages, null);

        String prevLink = (page > 0)
                ? buildLink(effectiveDate, page - 1, cappedSize, sortParam) : null;
        String nextLink = ((long) (page + 1) * cappedSize < totalElements)
                ? buildLink(effectiveDate, page + 1, cappedSize, sortParam) : null;
        PageLinks links = PageLinks.of(nextLink, prevLink);

        return new TechnicianDayResult(
                PagedResponse.of(rows, pageMeta, links),
                etag);
    }

    // ─── Query builders ───────────────────────────────────────────────────────

    private static String buildDataQuery(String orderClause) {
        return "SELECT wo.id, wo.reference, wo.priority, wo.state, " +
               "  wo.scheduled_window_start, wo.scheduled_window_end, " +
               "  s.name AS site_name, " +
               "  TRIM(CONCAT_WS(', ', s.address_line1, s.city, s.postcode)) AS site_address, " +
               "  s.latitude, s.longitude, s.contact_phone, " +
               "  a.asset_tag, a.model AS asset_description, " +
               "  wo.description AS fault_summary, " +
               "  wo.resolution_deadline, wo.at_risk AS sla_at_risk " +
               "FROM work_order wo " +
               "JOIN site s ON s.id = wo.site_id " +
               "LEFT JOIN asset a ON a.id = wo.asset_id " +
               buildDayWhere() +
               " ORDER BY " + orderClause +
               " LIMIT ? OFFSET ?";
    }

    private static String buildMetaQuery() {
        return "SELECT COUNT(*) AS cnt, COALESCE(MAX(wo.version), 0) AS max_ver " +
               "FROM work_order wo " +
               "JOIN site s ON s.id = wo.site_id " +
               buildDayWhere();
    }

    private static String buildDayWhere() {
        return " WHERE wo.assigned_technician_id = ? " +
               "  AND (" +
               "    (wo.scheduled_window_start >= ? AND wo.scheduled_window_start < ?)" +
               "    OR " +
               "    (wo.state IN ('ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD')" +
               "     AND (wo.scheduled_window_start IS NULL OR wo.scheduled_window_start < ?))" +
               "  ) ";
    }

    private static String buildOrderClause(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return "wo.scheduled_window_start ASC NULLS LAST, " + PRIORITY_CASE + " ASC, wo.id ASC";
        }
        String[] parts = sortParam.split(",", 2);
        String field = parts[0].strip();
        String dir   = (parts.length > 1) ? parts[1].strip().toLowerCase() : "asc";

        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new InvalidSortException("sort", field);
        }
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new InvalidSortException("sort", sortParam);
        }

        String orderDir = "desc".equals(dir) ? "DESC" : "ASC";
        String nullsClause = "desc".equals(dir) ? "NULLS FIRST" : "NULLS LAST";

        if ("scheduledStart".equals(field)) {
            return "wo.scheduled_window_start " + orderDir + " " + nullsClause +
                   ", " + PRIORITY_CASE + " ASC, wo.id ASC";
        } else { // priority
            return PRIORITY_CASE + " " + orderDir +
                   ", wo.scheduled_window_start ASC NULLS LAST, wo.id ASC";
        }
    }

    // ─── ETag ─────────────────────────────────────────────────────────────────

    static String computeEtag(UUID technicianId, LocalDate date, long count, long maxVersion) {
        String input = technicianId + ":" + date + ":" + count + ":" + maxVersion;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return '"' + HexFormat.of().formatHex(hash) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number n) return n.longValue();
        return Long.parseLong(obj.toString());
    }

    private static Instant toInstant(Timestamp ts) {
        return (ts != null) ? ts.toInstant() : null;
    }

    private static String buildLink(LocalDate date, int pg, int sz, String sort) {
        StringBuilder sb = new StringBuilder("/api/v1/technicians/me/work-orders?page=")
                .append(pg).append("&size=").append(sz)
                .append("&date=").append(date);
        if (sort != null && !sort.isBlank()) {
            sb.append("&sort=").append(sort);
        }
        return sb.toString();
    }

    // ─── Result type ──────────────────────────────────────────────────────────

    /**
     * Container returned by {@link #findDayJobs}: includes the page and the
     * pre-computed ETag so the controller can short-circuit with 304 before building
     * the full response body.
     */
    public record TechnicianDayResult(PagedResponse<TechnicianJobSummary> page, String etag) {

        static TechnicianDayResult empty(int size) {
            return new TechnicianDayResult(PagedResponse.empty(size), computeEmptyEtag(size));
        }

        private static String computeEmptyEtag(int size) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(("empty:" + size).getBytes(StandardCharsets.UTF_8));
                return '"' + HexFormat.of().formatHex(hash) + '"';
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
    }
}
