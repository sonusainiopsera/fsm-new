package com.fieldservice.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC-based assertions against Hibernate Envers audit tables.
 *
 * <p>Queries {@code *_AUD} tables and {@code REVINFO} directly rather than using the
 * Envers API so that tests are not coupled to the Envers reader surface and remain
 * valid even if the Envers version or configuration changes.
 *
 * <h2>Revision types</h2>
 * <ul>
 *   <li>{@code 0} — INSERT (entity created)</li>
 *   <li>{@code 1} — UPDATE (entity modified)</li>
 *   <li>{@code 2} — DELETE (entity removed)</li>
 * </ul>
 */
public final class AuditAssertions {

    /** Envers revision-type constant: entity was created in this revision. */
    public static final short REV_INSERT = 0;
    /** Envers revision-type constant: entity was updated in this revision. */
    public static final short REV_UPDATE = 1;
    /** Envers revision-type constant: entity was deleted in this revision. */
    public static final short REV_DELETE = 2;

    private AuditAssertions() {}

    /**
     * A single revision entry from an {@code *_AUD} table joined to {@code REVINFO}.
     *
     * @param revNumber    the sequential revision number
     * @param revType      0=INSERT, 1=UPDATE, 2=DELETE
     * @param revTimestamp epoch millis of the revision
     * @param actorUserId  the actor recorded in REVINFO (may be null for system operations)
     * @param traceId      request trace-id recorded in REVINFO (may be null)
     */
    public record RevisionRecord(
            int revNumber,
            short revType,
            long revTimestamp,
            String actorUserId,
            String traceId
    ) {
        /** Returns the revision timestamp as an {@link Instant}. */
        public Instant occurredAt() {
            return Instant.ofEpochMilli(revTimestamp);
        }
    }

    /**
     * Queries all revisions for {@code entityId} from the given {@code audTableName},
     * ordered by revision number ascending.
     *
     * @param dataSource   live datasource (obtained from Spring context)
     * @param audTableName bare table name, e.g. {@code "work_order_aud"}
     * @param entityId     UUID of the audited entity
     * @return ordered list of revision records; empty if the entity has no revisions
     */
    public static List<RevisionRecord> findRevisions(
            DataSource dataSource,
            String audTableName,
            UUID entityId) {

        String sql = "SELECT a.\"REV\", a.\"REVTYPE\", r.\"REVTSTMP\", r.actor_user_id, r.trace_id"
                + " FROM " + audTableName + " a"
                + " JOIN \"REVINFO\" r ON a.\"REV\" = r.\"REV\""
                + " WHERE a.id = CAST(? AS uuid)"
                + " ORDER BY a.\"REV\"";

        List<RevisionRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new RevisionRecord(
                            rs.getInt(1),
                            rs.getShort(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getString(5)
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("AuditAssertions.findRevisions failed for " + audTableName, e);
        }
        return records;
    }

    /**
     * Asserts that exactly {@code expectedCount} revisions exist for the given entity.
     */
    public static void assertRevisionCount(
            DataSource dataSource,
            String audTableName,
            UUID entityId,
            int expectedCount) {

        List<RevisionRecord> revisions = findRevisions(dataSource, audTableName, entityId);
        assertThat(revisions)
                .as("Expected %d revision(s) in %s for entity %s but found %d",
                        expectedCount, audTableName, entityId, revisions.size())
                .hasSize(expectedCount);
    }

    /**
     * Asserts the most recent revision for the entity has the given revision type.
     *
     * @param expectedRevType one of {@link #REV_INSERT}, {@link #REV_UPDATE},
     *                        {@link #REV_DELETE}
     */
    public static void assertLatestRevisionType(
            DataSource dataSource,
            String audTableName,
            UUID entityId,
            short expectedRevType) {

        List<RevisionRecord> revisions = findRevisions(dataSource, audTableName, entityId);
        assertThat(revisions)
                .as("No revisions found in %s for entity %s", audTableName, entityId)
                .isNotEmpty();
        RevisionRecord latest = revisions.get(revisions.size() - 1);
        assertThat(latest.revType())
                .as("Latest revision type in %s for entity %s", audTableName, entityId)
                .isEqualTo(expectedRevType);
    }
}
