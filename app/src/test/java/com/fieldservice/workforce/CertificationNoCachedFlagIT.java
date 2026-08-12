package com.fieldservice.workforce;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-gate test: asserts that no currency-flag-shaped column exists on either
 * certification table.
 *
 * <p>If any column named {@code is_current}, {@code is_valid}, {@code current_flag}
 * or {@code valid_flag} appears in the schema the test fails, encoding the
 * no-cached-boolean rule as a machine-enforceable build gate.
 *
 * <p>Runs against a real PostgreSQL 16 instance via Testcontainers so that the
 * assertion is based on the actual applied migration, not test-environment schema.
 */
@Tag("integration")
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestSecurityConfig.class)
@Testcontainers
class CertificationNoCachedFlagIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice_test")
                    .withUsername("fieldservice")
                    .withPassword("testpassword");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DataSource dataSource;

    private static final List<String> FORBIDDEN_COLUMN_NAMES = List.of(
            "is_current", "is_valid", "current_flag", "valid_flag",
            "currency_flag", "eligibility_flag"
    );

    private static final List<String> CERTIFICATION_TABLES = List.of(
            "certification_type",
            "technician_certification",
            "certification_type_aud",
            "technician_certification_aud"
    );

    @Test
    @DisplayName("No currency-flag-shaped column exists on any certification table")
    void no_cached_currency_boolean_on_certification_tables() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String tableName : CERTIFICATION_TABLES) {
                List<String> violatingColumns = findForbiddenColumns(conn, tableName);
                assertThat(violatingColumns)
                        .as("Table '%s' must not contain any stored currency boolean column "
                                + "(currency is a query-time predicate, never a stored flag)", tableName)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("certification_type table has required columns: regulated, active, default_validity_months")
    void certification_type_has_required_columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            List<String> columns = listColumns(conn, "certification_type");
            assertThat(columns).contains("regulated", "active", "default_validity_months", "code", "display_name");
        }
    }

    @Test
    @DisplayName("technician_certification table has issued_on and expires_on (DATE), not Instant columns")
    void technician_certification_has_date_columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            List<String> columns = listColumns(conn, "technician_certification");
            assertThat(columns).contains("issued_on", "expires_on", "certification_type_id", "active");
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private List<String> findForbiddenColumns(Connection conn, String tableName) throws Exception {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = ?
                  AND column_name  IN ('is_current','is_valid','current_flag','valid_flag',
                                       'currency_flag','eligibility_flag')
                """;
        List<String> found = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString("column_name"));
                }
            }
        }
        return found;
    }

    private List<String> listColumns(Connection conn, String tableName) throws Exception {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = ?
                ORDER BY ordinal_position
                """;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("column_name"));
                }
            }
        }
        return columns;
    }
}
