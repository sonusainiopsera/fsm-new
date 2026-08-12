package com.fieldservice.app.schema;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the production Flyway migration set produces the full Envers schema:
 * every audited entity has its {@code *_aud} table, {@code REVINFO} exists, and the
 * {@code revinfo_seq} sequence is present.
 *
 * <p>A newly added {@code @Audited} entity without a matching migration will fail
 * this test before it fails mysteriously at runtime.
 */
class AuditSchemaShapeIT extends AbstractIntegrationTest {

    /** All *_aud tables that must exist after running every migration. */
    private static final List<String> EXPECTED_AUD_TABLES = List.of(
            "work_order_aud",
            "assignment_aud",
            "technician_certification_aud",
            "app_user_aud",
            "site_aud",
            "sla_policy_aud",
            "role_assignment_aud",
            "login_audit_aud",
            "part_aud",
            "stock_location_aud",
            "work_order_hold_aud",
            "work_order_part_aud",
            "retention_policy_aud",
            "purge_run_aud",
            "dsar_request_aud"
    );

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("REVINFO table exists after all migrations")
    void revinfo_table_exists() throws Exception {
        assertTableExists("REVINFO");
    }

    @Test
    @DisplayName("revinfo_seq sequence exists after all migrations")
    void revinfo_sequence_exists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.sequences"
                             + " WHERE sequence_schema = 'public' AND sequence_name = 'revinfo_seq'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1))
                        .as("revinfo_seq should exist as a public sequence")
                        .isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("every audited entity has its *_aud table")
    void every_audited_entity_has_aud_table() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String table : EXPECTED_AUD_TABLES) {
            if (!tableExists(table)) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("Audit tables missing from migrated schema — add a Flyway migration for each")
                .isEmpty();
    }

    @Test
    @DisplayName("work_order_aud has REV foreign key referencing REVINFO")
    void work_order_aud_references_revinfo() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.referential_constraints rc"
                             + " JOIN information_schema.table_constraints tc"
                             + " ON rc.constraint_name = tc.constraint_name"
                             + " WHERE tc.table_name = 'work_order_aud'"
                             + " AND rc.unique_constraint_name IN ("
                             + "   SELECT constraint_name FROM information_schema.table_constraints"
                             + "   WHERE table_name = 'revinfo' OR table_name = 'REVINFO'"
                             + " )")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1))
                        .as("work_order_aud should have an FK to REVINFO")
                        .isGreaterThanOrEqualTo(1L);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private void assertTableExists(String tableName) throws Exception {
        assertThat(tableExists(tableName))
                .as("Table '%s' should exist in the migrated schema", tableName)
                .isTrue();
    }

    private boolean tableExists(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables"
                             + " WHERE table_schema = 'public'"
                             + " AND LOWER(table_name) = LOWER(?)")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }
}
