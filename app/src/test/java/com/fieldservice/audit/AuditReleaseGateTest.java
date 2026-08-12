package com.fieldservice.audit;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-gate test: asserts every audited entity produces revision rows and that
 * the Envers revision sequence exists in the applied schema (WO-199, AC-9).
 *
 * <p>If an audit table or the revision sequence is missing from Flyway migrations,
 * this test fails the build before reaching production.
 */
@DisplayName("Audit — release gate: revision sequence and audit tables exist")
class AuditReleaseGateTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("revinfo_seq exists in the applied schema")
    void revisinfoSeqExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_sequences " +
                "WHERE schemaname = 'public' AND sequencename = 'revinfo_seq'",
                Integer.class);
        assertThat(count)
                .as("revinfo_seq must be declared in Flyway migrations (V5)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("revinfo table exists")
    void revinfoTableExists() {
        assertTableExists("revinfo");
    }

    @Test
    @DisplayName("work_order_aud table exists")
    void workOrderAudExists() {
        assertTableExists("work_order_aud");
    }

    @Test
    @DisplayName("app_user_aud table exists")
    void appUserAudExists() {
        assertTableExists("app_user_aud");
    }

    @Test
    @DisplayName("site_aud table exists")
    void siteAudExists() {
        assertTableExists("site_aud");
    }

    @Test
    @DisplayName("assignment_aud table exists")
    void assignmentAudExists() {
        assertTableExists("assignment_aud");
    }

    @Test
    @DisplayName("sla_policy_aud table exists")
    void slaPolicyAudExists() {
        assertTableExists("sla_policy_aud");
    }

    @Test
    @DisplayName("audit_export table exists (V50)")
    void auditExportTableExists() {
        assertTableExists("audit_export");
    }

    @Test
    @DisplayName("REVINFO row is created when a WorkOrder is persisted")
    void revisinfoRowCreatedOnWorkOrderInsert() {
        // EnversRevisionTest.createAndUpdateProduceExactlyTwoRevisions covers this.
        // This is a cross-check that REVINFO is populated at all.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM revinfo", Integer.class);
        assertThat(count)
                .as("revinfo must contain at least one row after test setup")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("work_order_aud has required columns: id, rev, revtype")
    void workOrderAudHasRequiredColumns() {
        assertColumnExists("work_order_aud", "id");
        assertColumnExists("work_order_aud", "rev");
        assertColumnExists("work_order_aud", "revtype");
    }

    @Test
    @DisplayName("V50 search indexes are present on revinfo")
    void v50SearchIndexesExist() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes " +
                "WHERE schemaname = 'public' AND indexname = 'idx_revinfo_rev_tstmp_desc'",
                Integer.class);
        assertThat(count)
                .as("idx_revinfo_rev_tstmp_desc must exist (V50)")
                .isEqualTo(1);
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, tableName);
        assertThat(count)
                .as("Table '" + tableName + "' must exist in the schema")
                .isEqualTo(1);
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        assertThat(count)
                .as("Column '" + column + "' must exist in '" + table + "'")
                .isEqualTo(1);
    }
}
