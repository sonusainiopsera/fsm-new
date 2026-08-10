package com.fieldservice.app.db;

import com.fieldservice.app.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Flyway migration correctness, schema presence, CHECK constraints,
 * unique constraints, and indexes via information_schema and pg_indexes queries.
 *
 * Acceptance criteria covered: AC-1, AC-2 (table existence), AC-3, AC-5, AC-7, AC-8.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    // ── AC-1: migrations apply cleanly and re-run is a no-op ──────────────

    @Test
    @DisplayName("Re-applying Flyway migrations is idempotent — no exception")
    void flyway_rerun_is_idempotent() {
        int applied = flyway.migrate().migrationsExecuted;
        assertThat(applied).isZero(); // all already applied by Spring context startup
    }

    // ── Required tables exist ──────────────────────────────────────────────

    @Test
    @DisplayName("All required domain tables exist")
    void required_tables_exist() {
        for (String table : new String[]{
                "app_user", "role", "user_role",
                "customer", "site", "asset",
                "technician", "technician_certification",
                "work_order", "assignment",
                "part", "stock_location", "stock_balance", "stock_ledger",
                "sla_policy"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("Table '%s' must exist", table).isEqualTo(1);
        }
    }

    // ── password_hash column width ─────────────────────────────────────────

    @Test
    @DisplayName("app_user.password_hash is at least 60 characters wide (varchar(72))")
    void password_hash_column_is_wide_enough() {
        Integer maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_name = 'app_user' AND column_name = 'password_hash'",
                Integer.class);
        assertThat(maxLen).as("password_hash must fit 60-char BCrypt + algorithm prefix")
                .isGreaterThanOrEqualTo(72);
    }

    // ── AC-3: work_order state CHECK constraint ────────────────────────────

    @Test
    @DisplayName("work_order state CHECK constraint exists in pg_constraint")
    void work_order_state_check_constraint_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c " +
                "JOIN pg_class r ON r.oid = c.conrelid " +
                "WHERE r.relname = 'work_order' AND c.contype = 'c' " +
                "AND c.conname LIKE '%state%'",
                Integer.class);
        assertThat(count).as("work_order state CHECK constraint must exist").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("INSERT with out-of-vocabulary state is rejected by the database")
    void out_of_vocabulary_state_insert_is_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO work_order (id, title, state, site_id, version) " +
                "VALUES (gen_random_uuid(), 'Test WO', 'INVALID_STATE', " +
                "(SELECT id FROM site LIMIT 1), 0)"))
                .hasMessageContaining("chk_work_order_state");
    }

    // ── AC-5: stock_balance non-negative CHECK ─────────────────────────────

    @Test
    @DisplayName("stock_balance non-negative CHECK constraint exists")
    void stock_balance_non_negative_check_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c " +
                "JOIN pg_class r ON r.oid = c.conrelid " +
                "WHERE r.relname = 'stock_balance' AND c.contype = 'c' " +
                "AND c.conname LIKE '%non_negative%'",
                Integer.class);
        assertThat(count).as("stock_balance non-negative CHECK must exist").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Direct UPDATE driving stock_balance negative is rejected by the database")
    void negative_stock_balance_update_is_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE stock_balance SET quantity_on_hand = -1 " +
                "WHERE id = (SELECT id FROM stock_balance LIMIT 1)"))
                .hasMessageContaining("chk_stock_balance_non_negative");
    }

    // ── AC-7: sla_policy seeded ────────────────────────────────────────────

    @Test
    @DisplayName("sla_policy has seeded rows for CRITICAL, HIGH, MEDIUM, LOW")
    void sla_policy_has_seeded_rows() {
        for (String priority : new String[]{"CRITICAL", "HIGH", "MEDIUM", "LOW"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sla_policy WHERE priority = ?",
                    Integer.class, priority);
            assertThat(count).as("sla_policy must have a row for priority %s", priority)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("sla_policy at_risk_fraction defaults to 0.80")
    void sla_policy_default_at_risk_fraction() {
        Double fraction = jdbc.queryForObject(
                "SELECT CAST(at_risk_fraction AS DOUBLE PRECISION) FROM sla_policy WHERE priority = 'HIGH' LIMIT 1",
                Double.class);
        assertThat(fraction).as("at_risk_fraction should default to 0.80").isEqualTo(0.80, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── AC-8: required indexes ─────────────────────────────────────────────

    @Test
    @DisplayName("Required indexes exist in pg_indexes")
    void required_indexes_exist() {
        for (String[] tableAndPattern : new String[][]{
                {"work_order", "idx_work_order_created_at_id"},
                {"work_order", "idx_work_order_state"},
                {"work_order", "idx_work_order_assigned_technician"},
                {"technician_certification", "idx_tech_cert_technician_expires"},
                {"site", "idx_site_customer"},
                {"stock_ledger", "idx_stock_ledger_part_created"}}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                    Integer.class, tableAndPattern[0], tableAndPattern[1]);
            assertThat(count).as("Index '%s' on table '%s' must exist",
                    tableAndPattern[1], tableAndPattern[0]).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("stock_balance has unique constraint on (part_id, location_id)")
    void stock_balance_unique_constraint_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c " +
                "JOIN pg_class r ON r.oid = c.conrelid " +
                "WHERE r.relname = 'stock_balance' AND c.contype = 'u' " +
                "AND c.conname LIKE '%part%location%'",
                Integer.class);
        assertThat(count).as("stock_balance must have unique constraint on (part_id, location_id)")
                .isGreaterThanOrEqualTo(1);
    }

    // ── version columns exist ──────────────────────────────────────────────

    @Test
    @DisplayName("work_order, assignment, and stock_balance each have a version column")
    void version_columns_exist() {
        for (String[] tableAndCol : new String[][]{
                {"work_order", "version"},
                {"assignment", "version"},
                {"stock_balance", "version"}}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = ?",
                    Integer.class, tableAndCol[0], tableAndCol[1]);
            assertThat(count).as("Table '%s' must have column '%s'",
                    tableAndCol[0], tableAndCol[1]).isEqualTo(1);
        }
    }
}
