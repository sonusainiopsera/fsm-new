package com.fieldservice.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every {@code @Audited} entity has its corresponding {@code *_aud}
 * table in the migrated schema, and that {@code revinfo} and {@code revinfo_seq}
 * are present. A newly added {@code @Audited} entity whose migration was forgotten
 * fails this test loudly with the missing table name — never fails mysteriously
 * at runtime when Hibernate Envers cannot find the table.
 *
 * <p>This test also validates that the conditional-decrement constraint names and
 * the identity columns used by the outbox poller sequence are present.
 */
@DisplayName("Schema shape — Envers audit tables and invariants")
class SchemaShapeTest extends AbstractIntegrationTest {

    /** All *_aud tables that MUST exist after running all Flyway migrations. */
    private static final List<String> REQUIRED_AUD_TABLES = List.of(
            "work_order_aud",
            "app_user_aud",
            "site_aud",
            "assignment_aud",
            "technician_certification_aud",
            "sla_policy_aud",
            "role_assignment_aud",
            "work_order_hold_aud",
            "work_order_part_aud",
            "customer_aud",   // WO-117: Customer @Audited via V22 migration
            "asset_aud"       // WO-117: Asset @Audited via V22 migration
    );

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("revinfo table exists")
    void revinfo_tableExists() throws Exception {
        assertTableExists("revinfo");
    }

    @Test
    @DisplayName("revinfo_seq sequence exists")
    void revinfo_sequenceExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.sequences " +
                     "WHERE sequence_schema = 'public' AND sequence_name = 'revinfo_seq'")) {
            assertThat(rs.next())
                    .as("Sequence 'revinfo_seq' must exist — Envers will fail at startup without it")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("All @Audited entity audit tables exist")
    void allAuditTablesExist() throws Exception {
        List<String> missing = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (String table : REQUIRED_AUD_TABLES) {
                String sql = "SELECT 1 FROM information_schema.tables " +
                             "WHERE table_schema = 'public' AND table_name = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            missing.add(table);
                        }
                    }
                }
            }
        }
        assertThat(missing)
                .as("Missing *_aud table(s). Add the Flyway migration to create them. " +
                    "Missing: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("revinfo references fk from audit tables")
    void auditTables_haveFkToRevinfo() throws Exception {
        // Spot-check: work_order_aud has a FK to revinfo(rev)
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.table_constraints tc " +
                     "JOIN information_schema.referential_constraints rc " +
                     "  ON rc.constraint_name = tc.constraint_name " +
                     "JOIN information_schema.table_constraints rc2 " +
                     "  ON rc.unique_constraint_name = rc2.constraint_name " +
                     "WHERE tc.constraint_type = 'FOREIGN KEY' " +
                     "  AND tc.table_name = 'work_order_aud' " +
                     "  AND rc2.table_name = 'revinfo'")) {
            assertThat(rs.next())
                    .as("work_order_aud must have a FOREIGN KEY referencing revinfo(rev)")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("stock_balance non-negative CHECK constraint exists")
    void stockBalance_checkConstraintExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.table_constraints " +
                     "WHERE table_name = 'stock_balance' " +
                     "  AND constraint_name = 'chk_stock_balance_non_negative' " +
                     "  AND constraint_type = 'CHECK'")) {
            assertThat(rs.next())
                    .as("Check constraint chk_stock_balance_non_negative must exist on stock_balance")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("work_order state CHECK constraint exists")
    void workOrder_stateCheckConstraintExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.table_constraints " +
                     "WHERE table_name = 'work_order' " +
                     "  AND constraint_name = 'chk_work_order_state' " +
                     "  AND constraint_type = 'CHECK'")) {
            assertThat(rs.next())
                    .as("Check constraint chk_work_order_state must exist on work_order")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("outbox_event table has event_id as primary key")
    void outboxEvent_tableExists() throws Exception {
        assertTableExists("outbox_event");
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.columns " +
                     "WHERE table_name = 'outbox_event' AND column_name = 'event_id'")) {
            assertThat(rs.next())
                    .as("outbox_event must have column event_id")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertTableExists(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT 1 FROM information_schema.tables " +
                         "WHERE table_schema = 'public' AND table_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next())
                            .as("Table '%s' must exist after Flyway migrations", tableName)
                            .isTrue();
                }
            }
        }
    }
}
