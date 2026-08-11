package com.fieldservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC-based helpers for asserting Hibernate Envers revision history.
 *
 * <p>All queries hit the {@code *_aud} tables and {@code revinfo} directly over JDBC
 * to avoid coupling to the Envers AuditReader API surface. This makes the assertions
 * stable across Envers version upgrades and independent of Envers session state.
 *
 * <h3>Revision types</h3>
 * Envers encodes revision type as a {@code SMALLINT} in the {@code *_aud} table:
 * <ul>
 *   <li>{@code 0} = ADD (INSERT)</li>
 *   <li>{@code 1} = MOD (UPDATE)</li>
 *   <li>{@code 2} = DEL (DELETE)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   // Assert exactly two revisions for a work order
 *   List<RevisionRecord> revs = AuditAssertions.assertRevisionCount(
 *           jdbc, "work_order_aud", woId, 2);
 *
 *   // Assert the latest revision is a MOD
 *   assertThat(revs.get(1).revType()).isEqualTo(RevisionType.MOD);
 * }</pre>
 */
public final class AuditAssertions {

    /** Envers revision type constants (matches the SMALLINT stored in *_aud tables). */
    public enum RevisionType {
        ADD(0), MOD(1), DEL(2);

        private final int code;
        RevisionType(int code) { this.code = code; }
        public int code() { return code; }

        static RevisionType fromCode(int code) {
            return switch (code) {
                case 0 -> ADD;
                case 1 -> MOD;
                case 2 -> DEL;
                default -> throw new IllegalArgumentException("Unknown Envers revtype: " + code);
            };
        }
    }

    /**
     * A single Envers revision for an entity.
     *
     * @param revNumber   the revision number from {@code revinfo.rev}
     * @param revType     ADD / MOD / DEL
     * @param actorUserId the actor from {@code revinfo.actor_user_id} (may be {@code null}
     *                    for system-initiated changes)
     */
    public record RevisionRecord(
            int revNumber,
            RevisionType revType,
            String actorUserId
    ) {}

    private AuditAssertions() {}

    /**
     * Queries {@code audTable} for the given entity ID, asserts the count equals
     * {@code expectedCount}, and returns all revision records ordered by revision
     * number ascending.
     *
     * @param jdbc          JDBC template for the test database
     * @param audTable      name of the Envers audit table (e.g., {@code "work_order_aud"})
     * @param entityId      the entity primary key (UUID)
     * @param expectedCount expected number of revisions
     * @return ordered list of revision records (oldest first)
     */
    public static List<RevisionRecord> assertRevisionCount(
            JdbcTemplate jdbc,
            String audTable,
            UUID entityId,
            int expectedCount) {

        List<RevisionRecord> revisions = queryRevisions(jdbc, audTable, entityId);
        assertThat(revisions)
                .as("Expected %d revision(s) in %s for entity %s but found %d: %s",
                        expectedCount, audTable, entityId, revisions.size(), revisions)
                .hasSize(expectedCount);
        return revisions;
    }

    /**
     * Queries revision records for an entity without asserting count.
     * Ordered by revision number ascending (oldest first).
     */
    public static List<RevisionRecord> queryRevisions(
            JdbcTemplate jdbc,
            String audTable,
            UUID entityId) {

        String sql = """
                SELECT a.rev, a.revtype, r.actor_user_id
                FROM %s a
                JOIN revinfo r ON r.rev = a.rev
                WHERE a.id = ?
                ORDER BY a.rev ASC
                """.formatted(audTable);

        return jdbc.query(sql, AuditAssertions::mapRevisionRecord, entityId);
    }

    /**
     * Asserts that the most recent revision for the entity has the given revision type.
     *
     * @return the most recent revision record
     */
    public static RevisionRecord assertLatestRevisionType(
            JdbcTemplate jdbc,
            String audTable,
            UUID entityId,
            RevisionType expectedType) {

        List<RevisionRecord> revisions = queryRevisions(jdbc, audTable, entityId);
        assertThat(revisions)
                .as("No revisions found in %s for entity %s", audTable, entityId)
                .isNotEmpty();

        RevisionRecord latest = revisions.get(revisions.size() - 1);
        assertThat(latest.revType())
                .as("Latest revision type in %s for entity %s", audTable, entityId)
                .isEqualTo(expectedType);
        return latest;
    }

    /**
     * Asserts that the entity has exactly one revision and it is an ADD revision.
     * This is the expected state immediately after entity creation.
     */
    public static RevisionRecord assertSingleAddRevision(
            JdbcTemplate jdbc,
            String audTable,
            UUID entityId) {

        List<RevisionRecord> revisions = assertRevisionCount(jdbc, audTable, entityId, 1);
        assertThat(revisions.get(0).revType())
                .as("First and only revision must be ADD in %s for entity %s", audTable, entityId)
                .isEqualTo(RevisionType.ADD);
        return revisions.get(0);
    }

    // -------------------------------------------------------------------------
    // JDBC row mapper
    // -------------------------------------------------------------------------

    private static RevisionRecord mapRevisionRecord(ResultSet rs, int rowNum) throws SQLException {
        return new RevisionRecord(
                rs.getInt("rev"),
                RevisionType.fromCode(rs.getInt("revtype")),
                rs.getString("actor_user_id")
        );
    }
}
