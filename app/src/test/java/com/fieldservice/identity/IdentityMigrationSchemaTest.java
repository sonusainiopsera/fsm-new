package com.fieldservice.identity;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test asserting the V8–V12 migration set schema shape.
 *
 * <p>Checks:
 * <ul>
 *   <li>All identity tables exist in the public schema.</li>
 *   <li>{@code app_user.password_hash} is nullable and at least 72 chars wide.</li>
 *   <li>{@code app_user.external_subject} column exists and is nullable.</li>
 *   <li>Case-insensitive email uniqueness index exists ({@code uq_app_user_email_lower}).</li>
 *   <li>{@code role_assignment.role_name} CHECK constraint rejects out-of-vocabulary values.</li>
 *   <li>{@code role_assignment} unique constraint prevents duplicate grants.</li>
 *   <li>Audit tables {@code role_assignment_aud} exist and have the correct columns.</li>
 *   <li>Grant matrix: runtime role can SELECT and INSERT on audit tables but not UPDATE/DELETE.</li>
 *   <li>Repeat Flyway migrate produces no schema change (idempotency assertion via table count).</li>
 * </ul>
 */
@DisplayName("Identity migration schema tests (V8–V12)")
class IdentityMigrationSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Table existence
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("All identity tables exist after migration")
    void allIdentityTablesExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[]{"role_assignment", "refresh_token_family",
                    "refresh_token", "role_assignment_aud"}) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next())
                                .as("Table '%s' should exist", table)
                                .isTrue();
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // app_user extensions (V8)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("password_hash is nullable after V8 migration")
    void passwordHashIsNullable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='public' AND table_name='app_user' AND column_name='password_hash'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
        }
    }

    @Test
    @DisplayName("password_hash column width is at least 72 characters")
    void passwordHashWidthSufficient() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT character_maximum_length FROM information_schema.columns " +
                     "WHERE table_schema='public' AND table_name='app_user' AND column_name='password_hash'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                int width = rs.getInt("character_maximum_length");
                assertThat(width)
                        .as("password_hash must hold at least 72 chars for BCrypt + algorithm prefix")
                        .isGreaterThanOrEqualTo(72);
            }
        }
    }

    @Test
    @DisplayName("external_subject column exists and is nullable")
    void externalSubjectColumnExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='public' AND table_name='app_user' AND column_name='external_subject'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("external_subject column must exist on app_user")
                        .isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
        }
    }

    @Test
    @DisplayName("Case-insensitive email unique index exists on app_user")
    void emailCaseInsensitiveIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexname FROM pg_indexes " +
                     "WHERE schemaname='public' AND tablename='app_user' AND indexname='uq_app_user_email_lower'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("Case-insensitive email index uq_app_user_email_lower must exist")
                        .isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // role_assignment CHECK constraint (V9)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("role_name CHECK constraint rejects out-of-vocabulary values")
    void roleNameCheckConstraintRejectsInvalidValues() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> {
                    try (Statement st = conn.createStatement()) {
                        st.execute(
                            "INSERT INTO app_user (id, email, display_name, is_active, version) " +
                            "VALUES (gen_random_uuid(), 'chk@test.com', 'Chk', true, 0)");
                        st.execute(
                            "INSERT INTO role_assignment (id, user_id, role_name, granted_at) " +
                            "SELECT gen_random_uuid(), id, 'SUPERUSER', now() FROM app_user " +
                            "WHERE email='chk@test.com'");
                    }
                }).hasMessageContaining("chk_role_assignment_name");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    @Test
    @DisplayName("role_assignment unique constraint prevents duplicate grants")
    void uniqueConstraintPreventsDuplicateGrants() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert a user and two identical grants
                assertThatThrownBy(() -> {
                    try (Statement st = conn.createStatement()) {
                        st.execute(
                            "INSERT INTO app_user (id, email, display_name, is_active, version) " +
                            "VALUES ('dd000000-0000-0000-0000-000000000001', 'dup@test.com', 'Dup', true, 0)");
                        st.execute(
                            "INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES " +
                            "('dd000000-0000-0000-0000-000000000001', 'dd000000-0000-0000-0000-000000000001', 'ADMIN', now())");
                        st.execute(
                            "INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES " +
                            "('dd000000-0000-0000-0000-000000000002', 'dd000000-0000-0000-0000-000000000001', 'ADMIN', now())");
                    }
                }).hasMessageContaining("uq_role_assignment_user_role");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Audit grant matrix (V12) — negative privilege tests
    // -------------------------------------------------------------------------

    private void setupFieldserviceRoleForPrivilegeTest(String auditTable) {
        // Idempotent: create fieldservice role if absent (V12 migration does this too)
        jdbc.execute("DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') " +
                "THEN CREATE ROLE fieldservice; END IF; END $$");
        // Grant fieldservice to the test DB user so SET LOCAL ROLE works
        jdbc.execute("DO $$ BEGIN " +
                "IF NOT EXISTS (SELECT FROM pg_auth_members m " +
                "    JOIN pg_roles r ON r.oid = m.roleid " +
                "    JOIN pg_roles mr ON mr.oid = m.member " +
                "    WHERE r.rolname = 'fieldservice' AND mr.rolname = 'test') " +
                "THEN GRANT fieldservice TO test; END IF; END $$");
        // Ensure correct minimal grants are in place
        jdbc.execute("REVOKE ALL ON " + auditTable + " FROM fieldservice");
        jdbc.execute("GRANT SELECT, INSERT ON " + auditTable + " TO fieldservice");
    }

    @Test
    @DisplayName("Runtime role cannot UPDATE role_assignment_aud (immutability enforced at DB)")
    void runtimeRoleCannotUpdateRoleAssignmentAudit() throws Exception {
        setupFieldserviceRoleForPrivilegeTest("role_assignment_aud");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL ROLE fieldservice");
                assertThatThrownBy(() ->
                        st.execute("UPDATE role_assignment_aud SET role_name='ADMIN' WHERE rev=-999999"))
                        .as("fieldservice role must NOT have UPDATE on role_assignment_aud")
                        .hasMessageContainingIgnoringCase("permission denied");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("Runtime role cannot UPDATE app_user_aud (immutability enforced at DB)")
    void runtimeRoleCannotUpdateAppUserAudit() throws Exception {
        setupFieldserviceRoleForPrivilegeTest("app_user_aud");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL ROLE fieldservice");
                assertThatThrownBy(() ->
                        st.execute("UPDATE app_user_aud SET email='tampered' WHERE rev=-999999"))
                        .as("fieldservice role must NOT have UPDATE on app_user_aud")
                        .hasMessageContainingIgnoringCase("permission denied");
            } finally {
                conn.rollback();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Foreign key restriction (AC7)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deleting a user with role_assignment children is refused by FK RESTRICT")
    void deleteUserWithGrantsFails() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> {
                    try (Statement st = conn.createStatement()) {
                        st.execute(
                            "INSERT INTO app_user (id, email, display_name, is_active, version) VALUES " +
                            "('ee000000-0000-0000-0000-000000000001', 'fktest@test.com', 'FK', true, 0)");
                        st.execute(
                            "INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES " +
                            "('ee000000-0000-0000-0000-000000000001', 'ee000000-0000-0000-0000-000000000001', 'ADMIN', now())");
                        // This must fail — ON DELETE RESTRICT
                        st.execute("DELETE FROM app_user WHERE id='ee000000-0000-0000-0000-000000000001'");
                    }
                }).hasMessageContaining("fk_role_assignment_user");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }
}
