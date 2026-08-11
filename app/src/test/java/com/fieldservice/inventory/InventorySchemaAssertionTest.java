package com.fieldservice.inventory;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test asserting the V14 inventory foundation migration schema.
 *
 * <p>Checks:
 * <ul>
 *   <li>All inventory tables and V14 columns exist.</li>
 *   <li>Named CHECK constraints are present and enforced.</li>
 *   <li>UNIQUE constraints are enforced.</li>
 *   <li>Envers AUD tables exist for part and stock_location.</li>
 *   <li>Required indexes exist.</li>
 *   <li>A direct negative-quantity INSERT is rejected by stock_non_negative CHECK.</li>
 * </ul>
 */
@DisplayName("Inventory V14 schema assertion tests")
class InventorySchemaAssertionTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // -------------------------------------------------------------------------
    // Column existence on part (V14 additions)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("part table has all V14 columns")
    void partTableHasV14Columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String col : new String[]{"part_number", "description", "unit_of_measure",
                    "reorder_point", "reorder_quantity"}) {
                assertColumnExists(conn, "part", col);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Column existence on stock_location (V14 additions)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stock_location has updated_at column")
    void stockLocationHasUpdatedAt() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertColumnExists(conn, "stock_location", "updated_at");
        }
    }

    // -------------------------------------------------------------------------
    // Column existence on stock_balance (V14 additions)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stock_balance has quantity_reserved and created_at columns")
    void stockBalanceHasV14Columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertColumnExists(conn, "stock_balance", "quantity_reserved");
            assertColumnExists(conn, "stock_balance", "created_at");
        }
    }

    // -------------------------------------------------------------------------
    // CHECK constraints
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stock_non_negative CHECK constraint prevents negative quantity_on_hand")
    void stockNonNegativeCheckRejectsNegativeQuantity() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Insert a part and location to satisfy FKs
            conn.createStatement().executeUpdate(
                    "INSERT INTO part(id, sku, name, unit, part_number) VALUES " +
                    "('f0000000-ffff-0000-0000-000000000001', 'CHKSKU', 'CheckPart', 'EACH', 'CHK-001')");
            conn.createStatement().executeUpdate(
                    "INSERT INTO stock_location(id, name, location_type) VALUES " +
                    "('f0000000-ffff-0000-0000-000000000002', 'CheckLoc', 'WAREHOUSE')");

            assertThatThrownBy(() ->
                    conn.createStatement().executeUpdate(
                            "INSERT INTO stock_balance(id, part_id, location_id, quantity_on_hand, version) VALUES " +
                            "('f0000000-ffff-0000-0000-000000000003', " +
                            "'f0000000-ffff-0000-0000-000000000001', " +
                            "'f0000000-ffff-0000-0000-000000000002', -1, 0)"))
                    .as("Negative quantity_on_hand must be rejected by stock_non_negative CHECK")
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("stock_non_negative");

            conn.createStatement().executeUpdate(
                    "DELETE FROM stock_location WHERE id = 'f0000000-ffff-0000-0000-000000000002'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM part WHERE id = 'f0000000-ffff-0000-0000-000000000001'");
        }
    }

    @Test
    @DisplayName("VEHICLE location requires technician_id (technician consistency CHECK)")
    void vehicleLocationRequiresTechnicianId() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() ->
                    conn.createStatement().executeUpdate(
                            "INSERT INTO stock_location(id, name, location_type, technician_id) VALUES " +
                            "('f0000000-ffff-0000-0000-000000000010', 'Bad Vehicle', 'VEHICLE', NULL)"))
                    .as("VEHICLE location without technician_id must be rejected by CHECK constraint")
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    @DisplayName("WAREHOUSE location forbids technician_id (technician consistency CHECK)")
    void warehouseLocationForbidsTechnicianId() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() ->
                    conn.createStatement().executeUpdate(
                            "INSERT INTO stock_location(id, name, location_type, technician_id) VALUES " +
                            "('f0000000-ffff-0000-0000-000000000011', 'Bad Warehouse', 'WAREHOUSE', " +
                            "'00000000-0000-0000-0000-000000000011')"))
                    .as("WAREHOUSE location with technician_id must be rejected by CHECK constraint")
                    .isInstanceOf(Exception.class);
        }
    }

    // -------------------------------------------------------------------------
    // UNIQUE constraint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate (part_id, location_id) in stock_balance is rejected")
    void duplicateStockBalanceRejected() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                    "INSERT INTO part(id, sku, name, unit, part_number) VALUES " +
                    "('f0000000-ffff-0000-0000-000000000021', 'DUPSKU', 'DupPart', 'EACH', 'DUP-001')");
            conn.createStatement().executeUpdate(
                    "INSERT INTO stock_location(id, name, location_type) VALUES " +
                    "('f0000000-ffff-0000-0000-000000000022', 'DupLoc', 'WAREHOUSE')");
            conn.createStatement().executeUpdate(
                    "INSERT INTO stock_balance(id, part_id, location_id, quantity_on_hand, version) VALUES " +
                    "('f0000000-ffff-0000-0000-000000000023', " +
                    "'f0000000-ffff-0000-0000-000000000021', " +
                    "'f0000000-ffff-0000-0000-000000000022', 5, 0)");

            assertThatThrownBy(() ->
                    conn.createStatement().executeUpdate(
                            "INSERT INTO stock_balance(id, part_id, location_id, quantity_on_hand, version) VALUES " +
                            "('f0000000-ffff-0000-0000-000000000024', " +
                            "'f0000000-ffff-0000-0000-000000000021', " +
                            "'f0000000-ffff-0000-0000-000000000022', 3, 0)"))
                    .as("Duplicate (part_id, location_id) must be rejected by uq_stock_balance_part_location")
                    .isInstanceOf(Exception.class);

            conn.createStatement().executeUpdate(
                    "DELETE FROM stock_balance WHERE id = 'f0000000-ffff-0000-0000-000000000023'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM stock_location WHERE id = 'f0000000-ffff-0000-0000-000000000022'");
            conn.createStatement().executeUpdate(
                    "DELETE FROM part WHERE id = 'f0000000-ffff-0000-0000-000000000021'");
        }
    }

    // -------------------------------------------------------------------------
    // Envers AUD tables
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Envers AUD tables exist for part and stock_location")
    void enversAudTablesExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[]{"part_aud", "stock_location_aud"}) {
                assertTableExists(conn, table);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Indexes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("idx_stock_balance_part_loc index exists")
    void stockBalancePartLocIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=?")) {
            ps.setString(1, "idx_stock_balance_part_loc");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Index idx_stock_balance_part_loc should exist")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("unique index uq_part_part_number exists")
    void partNumberUniqueIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=?")) {
            ps.setString(1, "uq_part_part_number");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Index uq_part_part_number should exist")
                        .isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void assertTableExists(Connection conn, String tableName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Table '%s' should exist", tableName)
                        .isTrue();
            }
        }
    }

    private static void assertColumnExists(Connection conn, String tableName, String columnName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Column '%s.%s' should exist", tableName, columnName)
                        .isTrue();
            }
        }
    }
}
