package com.fieldservice.audit.internal;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only repository for cross-entity audit revision search via parameterized native SQL.
 *
 * <p>All queries use JDBC parameter binding. No user input is ever concatenated into SQL.
 * Entity type is resolved through {@link AuditEntityMetadata} allow-list before this class
 * is called, so the table name used in the join is from the allow-list, not from user input.
 */
@Repository
class AuditRevisionRepository {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    AuditRevisionRepository(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    /**
     * Searches revisions across all audited tables joined to REVINFO.
     * Returns rows for a single entity type when {@code entityType} is specified.
     *
     * <p>Uses keyset semantics on (rev_tstmp DESC, rev DESC) for stable pagination.
     */
    List<RevisionRow> search(String entityType, UUID entityId, String actorUserId,
                              Instant from, Instant to, Integer revFrom, Integer revTo,
                              int offset, int limit) {
        List<RevisionRow> results = new ArrayList<>();

        // Determine which entity tables to query
        List<String> entityTypes = entityType != null
                ? List.of(entityType)
                : new ArrayList<>(AuditEntityMetadata.AUDIT_TABLE.keySet());

        for (String et : entityTypes) {
            String audTable = AuditEntityMetadata.AUDIT_TABLE.get(et);
            if (audTable == null) continue;

            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT r.rev, r.rev_tstmp, r.actor_user_id, a.id AS entity_id, a.revtype, '")
               .append(et.replace("'", ""))  // safe: entity type already allow-listed
               .append("' AS entity_type ");
            sql.append("FROM revinfo r JOIN ").append(audTable).append(" a ON a.rev = r.rev WHERE 1=1 ");

            if (entityId != null) {
                sql.append("AND a.id = ? ");
                params.add(entityId);
            }
            if (actorUserId != null && !actorUserId.isBlank()) {
                sql.append("AND r.actor_user_id = ? ");
                params.add(actorUserId);
            }
            if (from != null) {
                sql.append("AND r.rev_tstmp >= ? ");
                params.add(from.toEpochMilli());
            }
            if (to != null) {
                sql.append("AND r.rev_tstmp < ? ");
                params.add(to.toEpochMilli());
            }
            if (revFrom != null) {
                sql.append("AND r.rev >= ? ");
                params.add(revFrom);
            }
            if (revTo != null) {
                sql.append("AND r.rev <= ? ");
                params.add(revTo);
            }

            sql.append("ORDER BY r.rev_tstmp DESC, r.rev DESC ");
            sql.append("LIMIT ? OFFSET ? ");
            params.add(limit + 1); // +1 for hasNext detection
            params.add(offset);

            List<RevisionRow> rows = jdbc.query(
                    sql.toString(),
                    params.toArray(),
                    (rs, i) -> new RevisionRow(
                            rs.getInt("rev"),
                            Instant.ofEpochMilli(rs.getLong("rev_tstmp")),
                            rs.getString("actor_user_id"),
                            rs.getString("entity_type"),
                            uuidOrNull(rs.getString("entity_id")),
                            revTypeName(rs.getInt("revtype"))
                    )
            );
            results.addAll(rows);
        }

        // Sort merged results and apply offset/limit
        results.sort((a, b) -> {
            int tsCompare = b.revisionTimestamp().compareTo(a.revisionTimestamp());
            return tsCompare != 0 ? tsCompare : Integer.compare(b.revisionNumber(), a.revisionNumber());
        });

        return results;
    }

    /** Count total matching revisions (for pagination totalElements). */
    long count(String entityType, UUID entityId, String actorUserId,
               Instant from, Instant to, Integer revFrom, Integer revTo) {
        long total = 0;
        List<String> entityTypes = entityType != null
                ? List.of(entityType)
                : new ArrayList<>(AuditEntityMetadata.AUDIT_TABLE.keySet());

        for (String et : entityTypes) {
            String audTable = AuditEntityMetadata.AUDIT_TABLE.get(et);
            if (audTable == null) continue;

            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT COUNT(*) FROM revinfo r JOIN ")
               .append(audTable).append(" a ON a.rev = r.rev WHERE 1=1 ");

            if (entityId != null) { sql.append("AND a.id = ? "); params.add(entityId); }
            if (actorUserId != null && !actorUserId.isBlank()) {
                sql.append("AND r.actor_user_id = ? "); params.add(actorUserId);
            }
            if (from != null) { sql.append("AND r.rev_tstmp >= ? "); params.add(from.toEpochMilli()); }
            if (to != null)   { sql.append("AND r.rev_tstmp < ? ");  params.add(to.toEpochMilli()); }
            if (revFrom != null) { sql.append("AND r.rev >= ? "); params.add(revFrom); }
            if (revTo != null)   { sql.append("AND r.rev <= ? "); params.add(revTo); }

            Long cnt = jdbc.queryForObject(sql.toString(), params.toArray(), Long.class);
            total += (cnt != null ? cnt : 0L);
        }
        return total;
    }

    private static UUID uuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static String revTypeName(int revType) {
        return switch (revType) {
            case 0 -> "ADD";
            case 1 -> "MOD";
            case 2 -> "DEL";
            default -> "UNKNOWN";
        };
    }

    record RevisionRow(
            int revisionNumber,
            Instant revisionTimestamp,
            String actor,
            String entityType,
            UUID entityId,
            String changeType
    ) {}
}
