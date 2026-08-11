package com.fieldservice.inventory;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the V13 migration produces the expected schema for inventory tables.
 * Uses information_schema queries rather than Hibernate validation so the assertions
 * describe the actual SQL schema, not the JPA mapping.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@Import(TestSecurityConfig.class)
class InventorySchemaIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_schema_test")
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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("part table has all required V13 columns")
    void part_table_has_required_columns() throws Exception {
        Set<String> cols = columnNames("part");
        assertThat(cols).contains(
                "id", "part_number", "name", "description",
                "unit_of_measure", "reorder_point", "reorder_quantity",
                "active", "created_at", "updated_at");
    }

    @Test
    @DisplayName("stock_location table has location_type and technician_id columns")
    void stock_location_table_has_required_columns() throws Exception {
        Set<String> cols = columnNames("stock_location");
        assertThat(cols).contains(
                "id", "name", "location_type", "technician_id", "site_id",
                "created_at", "updated_at");
    }

    @Test
    @DisplayName("stock_balance table has quantity_reserved and updated_at columns")
    void stock_balance_table_has_required_columns() throws Exception {
        Set<String> cols = columnNames("stock_balance");
        assertThat(cols).contains(
                "id", "part_id", "location_id",
                "quantity_on_hand", "quantity_reserved", "updated_at", "version");
    }

    @Test
    @DisplayName("part_aud and stock_location_aud Envers tables exist")
    void envers_audit_tables_exist() throws Exception {
        Set<String> tables = tableNames();
        assertThat(tables).contains("part_aud", "stock_location_aud");
    }

    @Test
    @DisplayName("CHECK constraints on part reorder columns exist")
    void part_check_constraints_exist() throws Exception {
        Set<String> constraints = constraintNames("part");
        assertThat(constraints).contains(
                "chk_part_reorder_point_nonneg",
                "chk_part_reorder_qty_nonneg");
    }

    @Test
    @DisplayName("CHECK constraint on stock_location vehicle/warehouse type exists")
    void stock_location_vehicle_check_constraint_exists() throws Exception {
        Set<String> constraints = constraintNames("stock_location");
        assertThat(constraints).contains("chk_stock_loc_vehicle_tech");
    }

    @Test
    @DisplayName("CHECK constraint on stock_balance quantity_reserved exists")
    void stock_balance_reserved_check_constraint_exists() throws Exception {
        Set<String> constraints = constraintNames("stock_balance");
        assertThat(constraints).contains("chk_stock_reserved_nonneg");
    }

    // ---- Helpers ---------------------------------------------------------------

    private Set<String> columnNames(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT column_name FROM information_schema.columns " +
                             "WHERE table_schema = 'public' AND table_name = '" + tableName + "'")) {
            Set<String> cols = new HashSet<>();
            while (rs.next()) cols.add(rs.getString(1));
            return cols;
        }
    }

    private Set<String> tableNames() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables " +
                             "WHERE table_schema = 'public'")) {
            Set<String> tables = new HashSet<>();
            while (rs.next()) tables.add(rs.getString(1));
            return tables;
        }
    }

    private Set<String> constraintNames(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT constraint_name FROM information_schema.table_constraints " +
                             "WHERE table_schema = 'public' AND table_name = '" + tableName + "'")) {
            Set<String> names = new HashSet<>();
            while (rs.next()) names.add(rs.getString(1));
            return names;
        }
    }
}
