package com.fieldservice.audit.internal;

import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionCursor;
import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionFilter;
import com.fieldservice.audit.api.AuditRevisionQueryService.RevisionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only repository for Envers audit revision data, using parameterized native SQL.
 *
 * <p>All query construction uses named parameters and allow-listed identifiers (table names
 * resolved through {@link AuditEntityAllowList}) so no user input is ever interpolated
 * into the SQL string itself.
 *
 * <p>This class exposes no write or delete operations — the audit surface is strictly
 * append-only from the application perspective.
 */
@Repository
class AuditRevisionRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditRevisionRepository.class);

    /** Maximum page size enforced here as a safety backstop. */
    static final int MAX_PAGE_SIZE = 50;

    private final NamedParameterJdbcTemplate jdbc;

    AuditRevisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Search ────────────────────────────────────────────────────────────────────────────

    /**
     * Searches revision history with optional filters and keyset pagination.
     *
     * <p>When {@code entityType} is specified, the query joins REVINFO to that entity's
     * AUD table, enabling {@code entityId} filtering. When {@code entityType} is absent,
     * only REVINFO-level filters (actor, date, rev range) are applied.
     *
     * @param filter  filter criteria
     * @param cursor  keyset cursor (null = first page)
     * @param size    page size, already clamped to [1, MAX_PAGE_SIZE]
     * @return list of revision summaries (at most {@code size} entries)
     */
    @Transactional(readOnly = true)
    List<RevisionSummary> search(RevisionFilter filter, RevisionCursor cursor, int size) {
        boolean hasEntityType = filter.entityType() != null;
        String auditTable = hasEntityType
                ? AuditEntityAllowList.auditTableFor(filter.entityType())
                : null;

        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (hasEntityType) {
            // Join to the specific AUD table for entityId filtering and REVTYPE.
            sql.append("""
                    SELECT r.REV             AS rev,
                           r.REVTSTMP        AS revtstmp,
                           r.actor_user_id   AS actor_user_id,
                           r.actor_role      AS actor_role,
                           e.id              AS entity_id,
                           e.REVTYPE         AS revtype
                    FROM REVINFO r
                    JOIN """).append(auditTable).append("""
                     e ON e.REV = r.REV
                    WHERE 1=1
                    """);
            if (filter.entityId() != null) {
                sql.append("  AND e.id = :entityId\n");
                params.addValue("entityId", filter.entityId());
            }
        } else {
            sql.append("""
                    SELECT r.REV             AS rev,
                           r.REVTSTMP        AS revtstmp,
                           r.actor_user_id   AS actor_user_id,
                           r.actor_role      AS actor_role,
                           NULL::UUID        AS entity_id,
                           NULL::SMALLINT    AS revtype
                    FROM REVINFO r
                    WHERE 1=1
                    """);
        }

        // REVINFO-level filters — always applied.
        if (filter.actorId() != null) {
            sql.append("  AND r.actor_user_id = :actorId\n");
            params.addValue("actorId", filter.actorId());
        }
        if (filter.from() != null) {
            sql.append("  AND r.REVTSTMP >= :fromMs\n");
            params.addValue("fromMs", filter.from().toEpochMilli());
        }
        if (filter.to() != null) {
            sql.append("  AND r.REVTSTMP <= :toMs\n");
            params.addValue("toMs", filter.to().toEpochMilli());
        }
        if (filter.revFrom() != null) {
            sql.append("  AND r.REV >= :revFrom\n");
            params.addValue("revFrom", filter.revFrom());
        }
        if (filter.revTo() != null) {
            sql.append("  AND r.REV <= :revTo\n");
            params.addValue("revTo", filter.revTo());
        }

        // Keyset cursor: stable ordering tie-break on (revtstmp DESC, rev DESC).
        if (cursor != null) {
            sql.append("  AND (r.REVTSTMP < :cursorTs OR (r.REVTSTMP = :cursorTs AND r.REV < :cursorRev))\n");
            params.addValue("cursorTs",  cursor.revTimestampMillis());
            params.addValue("cursorRev", cursor.rev());
        }

        sql.append("ORDER BY r.REVTSTMP DESC, r.REV DESC\n");
        sql.append("LIMIT :size");
        params.addValue("size", size);

        String entityType = filter.entityType();
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            UUID entityId = null;
            Object rawId = rs.getObject("entity_id");
            if (rawId != null) {
                entityId = rs.getObject("entity_id", UUID.class);
            }
            Short revtype = (Short) rs.getObject("revtype");
            String changeType = revtypeLabel(revtype);

            return new RevisionSummary(
                    rs.getInt("rev"),
                    Instant.ofEpochMilli(rs.getLong("revtstmp")),
                    rs.getString("actor_user_id"),
                    rs.getString("actor_role"),
                    entityType,
                    entityId,
                    changeType,
                    List.of());
        });
    }

    // ── Detail diff ──────────────────────────────────────────────────────────────────────

    /**
     * Loads the raw column values for a specific revision of an entity.
     *
     * @param auditTable  allow-listed audit table name
     * @param entityId    entity UUID
     * @param rev         revision number
     * @return column-value map, or empty if the row doesn't exist
     */
    @Transactional(readOnly = true)
    Map<String, Object> loadRevisionRow(String auditTable, UUID entityId, int rev) {
        String sql = "SELECT * FROM " + auditTable + " WHERE id = :entityId AND REV = :rev";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entityId", entityId)
                .addValue("rev", rev);

        List<Map<String, Object>> rows = jdbc.query(sql, params, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int colCount = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                row.put(rs.getMetaData().getColumnName(i).toLowerCase(), rs.getObject(i));
            }
            return row;
        });
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * Finds the revision number immediately preceding the given revision for this entity.
     *
     * @return the predecessor rev number, or -1 if none exists
     */
    @Transactional(readOnly = true)
    int findPredecessorRev(String auditTable, UUID entityId, int rev) {
        String sql = "SELECT MAX(REV) FROM " + auditTable
                + " WHERE id = :entityId AND REV < :rev";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("entityId", entityId)
                .addValue("rev", rev);
        Integer result = jdbc.queryForObject(sql, params, Integer.class);
        return result != null ? result : -1;
    }

    /**
     * Loads revision metadata from REVINFO for a given revision number.
     */
    @Transactional(readOnly = true)
    Map<String, Object> loadRevInfo(int rev) {
        String sql = "SELECT REV, REVTSTMP, actor_user_id, actor_role FROM REVINFO WHERE REV = :rev";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("rev", rev);
        List<Map<String, Object>> rows = jdbc.query(sql, params, (rs, rowNum) -> Map.of(
                "rev",          rs.getInt("REV"),
                "revtstmp",     rs.getLong("REVTSTMP"),
                "actor_user_id", rs.getString("actor_user_id") != null ? rs.getString("actor_user_id") : "SYSTEM",
                "actor_role",    rs.getString("actor_role") != null ? rs.getString("actor_role") : ""
        ));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    // ── Export ────────────────────────────────────────────────────────────────────────────

    /**
     * Counts the rows that would be returned by the given filter (no pagination).
     * Used to decide synchronous vs. asynchronous export path.
     */
    @Transactional(readOnly = true)
    long countSearch(RevisionFilter filter) {
        boolean hasEntityType = filter.entityType() != null;
        String auditTable = hasEntityType
                ? AuditEntityAllowList.auditTableFor(filter.entityType())
                : null;

        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (hasEntityType) {
            sql.append("SELECT COUNT(*) FROM REVINFO r JOIN ")
               .append(auditTable)
               .append(" e ON e.REV = r.REV WHERE 1=1\n");
            if (filter.entityId() != null) {
                sql.append("  AND e.id = :entityId\n");
                params.addValue("entityId", filter.entityId());
            }
        } else {
            sql.append("SELECT COUNT(*) FROM REVINFO r WHERE 1=1\n");
        }
        applyRevInfoFilters(sql, params, filter);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Runs an unbounded search for export (no cursor, up to the ceiling configured
     * in the service layer).
     */
    @Transactional(readOnly = true)
    List<RevisionSummary> searchForExport(RevisionFilter filter, int ceiling) {
        return search(filter, null, Math.min(ceiling, MAX_PAGE_SIZE));
    }

    // ── Private helpers ───────────────────────────────────────────────────────────────────

    private void applyRevInfoFilters(StringBuilder sql, MapSqlParameterSource params,
                                      RevisionFilter filter) {
        if (filter.actorId() != null) {
            sql.append("  AND r.actor_user_id = :actorId\n");
            params.addValue("actorId", filter.actorId());
        }
        if (filter.from() != null) {
            sql.append("  AND r.REVTSTMP >= :fromMs\n");
            params.addValue("fromMs", filter.from().toEpochMilli());
        }
        if (filter.to() != null) {
            sql.append("  AND r.REVTSTMP <= :toMs\n");
            params.addValue("toMs", filter.to().toEpochMilli());
        }
        if (filter.revFrom() != null) {
            sql.append("  AND r.REV >= :revFrom\n");
            params.addValue("revFrom", filter.revFrom());
        }
        if (filter.revTo() != null) {
            sql.append("  AND r.REV <= :revTo\n");
            params.addValue("revTo", filter.revTo());
        }
    }

    private static String revtypeLabel(Short revtype) {
        if (revtype == null) return "UNKNOWN";
        return switch (revtype) {
            case 0 -> "ADD";
            case 1 -> "MOD";
            case 2 -> "DEL";
            default -> "UNKNOWN";
        };
    }
}
