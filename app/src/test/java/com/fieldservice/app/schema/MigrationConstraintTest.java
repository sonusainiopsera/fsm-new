package com.fieldservice.app.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test that applies all Flyway migrations against a real
 * PostgreSQL 16 instance and verifies every required table, CHECK constraint, unique
 * constraint, and index.
 *
 * <p>Tagged {@code integration}: requires Docker. Run with:
 * {@code ./mvnw verify -pl app -Dgroups=integration}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class MigrationConstraintTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                     () -> "org.hibernate.dialect.PostgreSQLDialect");
        // JwtDecoder is provided by TestSecurityConfig; disable issuer-uri auto-config
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired
    DataSource dataSource;

    // ---- Table existence --------------------------------------------------------

    @Test
    @DisplayName("all required tables exist after migration")
    void all_required_tables_exist() throws SQLException {
        String[] tables = {
            "app_user", "role", "user_role", "customer", "site", "asset",
            "technician", "technician_certification", "work_order", "assignment",
            "part", "stock_location", "stock_balance", "stock_ledger", "sla_policy"
        };
        try (Connection conn = dataSource.getConnection()) {
            for (String table : tables) {
                assertThat(tableExists(conn, table))
                        .as("table '%s' should exist", table)
                        .isTrue();
            }
        }
    }

    // ---- CHECK constraint enforcement ------------------------------------------

    @Test
    @DisplayName("inserting work_order with out-of-vocabulary state is rejected by database")
    void work_order_invalid_state_rejected() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Insert prerequisite customer + site
            exec(conn, "INSERT INTO customer (id, name) VALUES " +
                       "('c1c1c1c1-0000-7fff-8000-000000000001', 'Test Corp')");
            exec(conn, "INSERT INTO site (id, name, customer_id) VALUES " +
                       "('b1b1b1b1-0000-7fff-8000-000000000001', 'Test Site', " +
                       "'c1c1c1c1-0000-7fff-8000-000000000001')");

            String badInsert = "INSERT INTO work_order " +
                    "(id, reference, state, priority, site_id) VALUES " +
                    "('a1a1a1a1-0000-7fff-8000-000000000001', 'WO-BAD', 'BOGUS', 'LOW', " +
                    "'b1b1b1b1-0000-7fff-8000-000000000001')";

            assertThatThrownBy(() -> exec(conn, badInsert))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("stock_balance negative quantity is rejected by database CHECK constraint")
    void stock_balance_negative_rejected() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO part (id, part_number, name) VALUES " +
                       "('d1d1d1d1-0000-7fff-8000-000000000001', 'P-TST-01', 'Test Part')");
            exec(conn, "INSERT INTO stock_location (id, name) VALUES " +
                       "('e1e1e1e1-0000-7fff-8000-000000000001', 'Test Loc')");
            exec(conn, "INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES " +
                       "('f1f1f1f1-0000-7fff-8000-000000000001', " +
                       "'d1d1d1d1-0000-7fff-8000-000000000001', " +
                       "'e1e1e1e1-0000-7fff-8000-000000000001', 10)");

            String badUpdate = "UPDATE stock_balance SET quantity_on_hand = -1 WHERE " +
                    "id = 'f1f1f1f1-0000-7fff-8000-000000000001'";

            assertThatThrownBy(() -> exec(conn, badUpdate))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ---- Required indexes -------------------------------------------------------

    @Test
    @DisplayName("required indexes exist in pg_indexes")
    void required_indexes_exist() throws SQLException {
        String[] indexes = {
            "idx_wo_created_at_id",
            "idx_wo_state",
            "idx_wo_assigned_tech",
            "idx_wo_site",
            "idx_site_customer",
            "idx_tech_cert_tech_expiry",
            "idx_stock_ledger_part_created",
            "idx_assignment_wo"
        };
        try (Connection conn = dataSource.getConnection()) {
            for (String idx : indexes) {
                assertThat(indexExists(conn, idx))
                        .as("index '%s' should exist", idx)
                        .isTrue();
            }
        }
    }

    // ---- SLA seed data ----------------------------------------------------------

    @Test
    @DisplayName("sla_policy has exactly 4 seeded rows (one per priority)")
    void sla_policy_seeded() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM sla_policy WHERE priority IN " +
                     "('LOW','MEDIUM','HIGH','CRITICAL')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(4);
        }
    }

    // ---- Unique constraints -----------------------------------------------------

    @Test
    @DisplayName("stock_balance unique constraint on (part_id, location_id)")
    void stock_balance_unique_constraint() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO part (id, part_number, name) VALUES " +
                       "('d2d2d2d2-0000-7fff-8000-000000000001', 'P-UNQ-01', 'Unique Part')");
            exec(conn, "INSERT INTO stock_location (id, name) VALUES " +
                       "('e2e2e2e2-0000-7fff-8000-000000000001', 'Unique Loc')");
            exec(conn, "INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES " +
                       "('f2f2f2f2-0000-7fff-8000-000000000001', " +
                       "'d2d2d2d2-0000-7fff-8000-000000000001', " +
                       "'e2e2e2e2-0000-7fff-8000-000000000001', 5)");

            String dupInsert = "INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES " +
                    "('f2f2f2f2-0000-7fff-8000-000000000002', " +
                    "'d2d2d2d2-0000-7fff-8000-000000000001', " +
                    "'e2e2e2e2-0000-7fff-8000-000000000001', 3)";

            assertThatThrownBy(() -> exec(conn, dupInsert))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ---- Helpers ----------------------------------------------------------------

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean indexExists(Connection conn, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
