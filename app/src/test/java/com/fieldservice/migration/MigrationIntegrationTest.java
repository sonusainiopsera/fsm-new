package com.fieldservice.migration;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests verifying the Flyway baseline migrations (V1–V4) against a real
 * PostgreSQL 16 instance.
 *
 * <p>Checks:
 * <ul>
 *   <li>All expected tables exist after migration.</li>
 *   <li>Critical constraints are enforced (invalid state, negative stock).</li>
 *   <li>{@code password_hash} column is wide enough to hold a 60-char BCrypt hash.</li>
 *   <li>V4 seed data (roles) is present after migration.</li>
 * </ul>
 */
@DisplayName("Flyway migration integrity tests")
class MigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private static final List<String> EXPECTED_TABLES = List.of(
            "app_user", "role", "user_role",
            "customer", "site", "asset",
            "technician", "technician_certification",
            "work_order", "assignment",
            "part", "stock_location", "stock_balance", "stock_ledger",
            "sla_policy"
    );

    @Test
    @DisplayName("All expected tables exist after migration")
    void allTablesExist() throws SQLException {
        List<String> missing = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (String table : EXPECTED_TABLES) {
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
                .as("Tables missing after migration")
                .isEmpty();
    }

    @Test
    @DisplayName("password_hash column is VARCHAR(72) — wide enough for BCrypt output")
    void passwordHashColumnWidth() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT character_maximum_length FROM information_schema.columns " +
                         "WHERE table_name = 'app_user' AND column_name = 'password_hash'";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                assertThat(rs.next()).isTrue();
                int width = rs.getInt(1);
                assertThat(width)
                        .as("password_hash column must be at least 72 characters wide")
                        .isGreaterThanOrEqualTo(72);
            }
        }
    }

    @Test
    @DisplayName("Invalid work_order state is rejected by CHECK constraint")
    void workOrderStateCheckConstraint_rejectsInvalidState() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String insertSite = "INSERT INTO customer (id, name, version) VALUES " +
                    "('eeeeeeee-0000-0000-0000-000000000001', 'Constraint Test Customer', 0)";
            try (Statement st = conn.createStatement()) {
                st.execute(insertSite);
            }

            String insertSiteRow = "INSERT INTO site (id, customer_id, name, version) VALUES " +
                    "('eeeeeeee-0000-0000-0000-000000000002', " +
                    "'eeeeeeee-0000-0000-0000-000000000001', 'Test Site', 0)";
            try (Statement st = conn.createStatement()) {
                st.execute(insertSiteRow);
            }

            String badInsert = "INSERT INTO work_order " +
                    "(id, site_id, customer_id, state, priority, version) VALUES " +
                    "('eeeeeeee-0000-0000-0000-000000000003', " +
                    "'eeeeeeee-0000-0000-0000-000000000002', " +
                    "'eeeeeeee-0000-0000-0000-000000000001', " +
                    "'OPEN', 'HIGH', 0)";

            assertThatThrownBy(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute(badInsert);
                }
            }).as("INSERT with invalid state 'OPEN' must be rejected by CHECK constraint")
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("chk_work_order_state");
        }
    }

    @Test
    @DisplayName("Negative stock_balance quantity is rejected by CHECK constraint")
    void stockBalanceNonNegativeConstraint_rejectsNegativeQuantity() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String insertPart = "INSERT INTO part (id, sku, name) VALUES " +
                    "('fffffff1-0000-0000-0000-000000000001', 'TEST-SKU-NEG', 'Negative Test Part')";
            try (Statement st = conn.createStatement()) {
                st.execute(insertPart);
            }

            String insertLocation = "INSERT INTO stock_location (id, name) VALUES " +
                    "('fffffff1-0000-0000-0000-000000000002', 'Test Warehouse')";
            try (Statement st = conn.createStatement()) {
                st.execute(insertLocation);
            }

            String badBalance = "INSERT INTO stock_balance " +
                    "(id, part_id, location_id, quantity_on_hand, version) VALUES " +
                    "('fffffff1-0000-0000-0000-000000000003', " +
                    "'fffffff1-0000-0000-0000-000000000001', " +
                    "'fffffff1-0000-0000-0000-000000000002', " +
                    "-1, 0)";

            assertThatThrownBy(() -> {
                try (Statement st = conn.createStatement()) {
                    st.execute(badBalance);
                }
            }).as("Negative quantity_on_hand must be rejected by CHECK constraint")
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("chk_stock_balance_non_negative");
        }
    }

    @Test
    @DisplayName("V4 seed data: all 5 role rows are present after migration")
    void seedRoles_allFivePresentAfterMigration() throws SQLException {
        List<String> roles = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM role ORDER BY name")) {
            while (rs.next()) {
                roles.add(rs.getString(1));
            }
        }
        assertThat(roles)
                .as("V4 seed must insert all 5 role rows")
                .containsExactlyInAnyOrder("ADMIN", "CUSTOMER", "DISPATCHER", "MANAGER", "TECHNICIAN");
    }

    @Test
    @DisplayName("V2 seed data: all 4 SLA policy rows are present after migration")
    void seedSlaPolicies_allFourPrioritiesPresent() throws SQLException {
        List<String> priorities = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT priority FROM sla_policy ORDER BY priority")) {
            while (rs.next()) {
                priorities.add(rs.getString(1));
            }
        }
        assertThat(priorities)
                .as("V2 seed must insert 4 SLA policy rows (one per priority)")
                .containsExactlyInAnyOrder("CRITICAL", "HIGH", "LOW", "MEDIUM");
    }

    @Test
    @DisplayName("A 60-character BCrypt hash persists and reads back without truncation")
    void passwordHash_60CharBcryptPersistsWithoutTruncation() throws SQLException {
        // BCrypt output format: $2a$10$<22-char salt><31-char hash> = 60 chars exactly
        String bcryptHash = "$2a$10$abcdefghijklmnopqrstuuVWXYZ0123456789abcdefghijklmnopqrs";
        assertThat(bcryptHash).hasSize(60);

        String userId = "dddddddd-0000-0000-0000-000000000099";
        try (Connection conn = dataSource.getConnection()) {
            String insert = "INSERT INTO app_user (id, email, password_hash, display_name, version) " +
                    "VALUES (?, ?, ?, 'BCrypt Test', 0)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setObject(1, java.util.UUID.fromString(userId));
                ps.setString(2, "bcrypt-test@example.com");
                ps.setString(3, bcryptHash);
                ps.executeUpdate();
            }

            String select = "SELECT password_hash FROM app_user WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(select)) {
                ps.setObject(1, java.util.UUID.fromString(userId));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    String retrieved = rs.getString(1);
                    assertThat(retrieved)
                            .as("60-character BCrypt hash must be stored and retrieved without truncation")
                            .isEqualTo(bcryptHash)
                            .hasSize(60);
                }
            }
        }
    }

    private static final List<String> EXPECTED_INDEXES = List.of(
            "idx_work_order_created_at_id",
            "idx_work_order_state",
            "idx_work_order_assigned_technician",
            "idx_technician_cert_lookup",
            "idx_site_customer_id",
            "idx_stock_ledger_part_created"
    );

    @Test
    @DisplayName("V3 required indexes exist in pg_indexes")
    void requiredIndexesExist() throws SQLException {
        List<String> missing = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (String indexName : EXPECTED_INDEXES) {
                String sql = "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, indexName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            missing.add(indexName);
                        }
                    }
                }
            }
        }
        assertThat(missing)
                .as("Indexes missing after V3 migration")
                .isEmpty();
    }

    @Test
    @DisplayName("Unique constraint on stock_balance(part_id, location_id) exists")
    void stockBalance_uniqueConstraintExists() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'stock_balance' " +
                    "  AND indexname = 'uq_stock_balance_part_location'";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                assertThat(rs.next())
                        .as("Unique index uq_stock_balance_part_location must exist")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Flyway schema_history confirms all 4 migrations applied cleanly")
    void flywayHistory_allMigrationsApplied() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM flyway_schema_history " +
                     "WHERE success = true AND type = 'SQL' " +
                     "ORDER BY installed_rank")) {
            List<String> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
            assertThat(versions)
                    .as("Flyway must have applied V1 through V4 cleanly")
                    .containsExactly("1", "2", "3", "4");
        }
    }
}
