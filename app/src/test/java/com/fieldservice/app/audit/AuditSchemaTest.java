package com.fieldservice.app.audit;

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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schema-level audit tests.
 *
 * <p>Asserts:
 * <ul>
 *   <li>All six audit tables exist (AC-1)</li>
 *   <li>REVINFO exists with required actor columns (AC-2, AC-3)</li>
 *   <li>password_hash is absent from app_user_aud (AC-4)</li>
 *   <li>BRIN index exists on work_order_aud(REV) (Flyway V5)</li>
 *   <li>Runtime role cannot UPDATE an audit row (AC-6)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class AuditSchemaTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("all six audit tables exist after V5 migration")
    void all_audit_tables_exist() throws SQLException {
        String[] tables = {
            "work_order_aud", "assignment_aud", "technician_certification_aud",
            "app_user_aud", "site_aud", "sla_policy_aud"
        };
        try (Connection conn = dataSource.getConnection()) {
            for (String table : tables) {
                assertThat(tableExists(conn, table))
                        .as("audit table '%s' should exist", table)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("REVINFO exists and contains all required actor columns")
    void revinfo_has_actor_columns() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(tableExists(conn, "revinfo")).isTrue();
            assertThat(columnExists(conn, "revinfo", "actor_user_id")).isTrue();
            assertThat(columnExists(conn, "revinfo", "actor_role")).isTrue();
            assertThat(columnExists(conn, "revinfo", "trace_id")).isTrue();
            assertThat(columnExists(conn, "revinfo", "client_ip")).isTrue();
        }
    }

    @Test
    @DisplayName("password_hash column is absent from app_user_aud (Restricted classification)")
    void password_hash_absent_from_app_user_aud() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(columnExists(conn, "app_user_aud", "password_hash"))
                    .as("password_hash must never appear in audit tables")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("BRIN index exists on work_order_aud(REV)")
    void brin_index_exists_on_work_order_aud() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexname, indexdef FROM pg_indexes " +
                     "WHERE schemaname = 'public' AND tablename = 'work_order_aud' " +
                     "AND indexname = 'brin_work_order_aud_rev'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("BRIN index brin_work_order_aud_rev should exist").isTrue();
                String indexDef = rs.getString("indexdef");
                assertThat(indexDef.toLowerCase()).contains("brin");
            }
        }
    }

    @Test
    @DisplayName("revinfo_seq sequence exists in the database")
    void revinfo_seq_exists() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.sequences " +
                     "WHERE sequence_schema = 'public' AND sequence_name = 'revinfo_seq'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    @DisplayName("runtime role (fieldservice) cannot UPDATE audit rows — immutability enforced by DB")
    void fieldservice_role_cannot_update_audit_rows() throws SQLException {
        // Create an application-level user with only the fieldservice role, then
        // try to UPDATE an audit table through that connection — must fail.
        try (Connection adminConn = dataSource.getConnection()) {
            try (Statement st = adminConn.createStatement()) {
                st.execute("CREATE USER audit_runtime_test WITH PASSWORD 'audit_pw_1'");
                st.execute("GRANT fieldservice TO audit_runtime_test");
                st.execute("GRANT CONNECT ON DATABASE fsapi_schema_test TO audit_runtime_test");
            }
        }

        String url = postgres.getJdbcUrl();
        try (Connection userConn = DriverManager.getConnection(url, "audit_runtime_test", "audit_pw_1")) {
            assertThatThrownBy(() -> {
                try (Statement st = userConn.createStatement()) {
                    st.execute("UPDATE work_order_aud SET reference = 'tampered' WHERE 1=0");
                }
            }).isInstanceOf(SQLException.class)
              .hasMessageContaining("permission denied");
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND LOWER(table_name) = LOWER(?)")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean columnExists(Connection conn, String tableName, String columnName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND LOWER(table_name) = LOWER(?) " +
                "AND LOWER(column_name) = LOWER(?)")) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
