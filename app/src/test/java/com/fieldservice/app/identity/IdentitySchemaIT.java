package com.fieldservice.app.identity;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.audit.AppRevision;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration tests for the identity module.
 *
 * <p>Applies the full migration set (V1–V10) against a real PostgreSQL 16 instance and asserts:
 * <ul>
 *   <li>Schema shape: all identity tables and columns exist (AC-1, AC-2, AC-3)</li>
 *   <li>Envers revision creation: insert + update AppUser yields two revisions (AC-4)</li>
 *   <li>Audit-table privilege: runtime role cannot UPDATE role_assignment_aud (AC-5)</li>
 *   <li>Credential round-trip: 60-char BCrypt hash and algorithm-prefixed variant survive (AC-6)</li>
 *   <li>FK restriction: deleting AppUser with role_assignment children is refused (AC-7)</li>
 *   <li>Case-insensitive email: same email in different case collides on uq_app_user_email_ci (AC-1)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class IdentitySchemaIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_identity_test")
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

    @Autowired DataSource                  dataSource;
    @Autowired EntityManager               entityManager;
    @Autowired PlatformTransactionManager  txManager;
    @Autowired AppUserRepository           appUserRepo;
    @Autowired RoleAssignmentRepository    roleAssignmentRepo;

    // ---- AC-1: Schema shape — app_user extended columns -------------------------

    @Test
    @DisplayName("app_user has all required identity columns after V8 migration")
    void appUser_requiredColumnsExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            for (String col : new String[]{"id","email","password_hash","active",
                    "display_name","external_subject","created_at","updated_at"}) {
                assertThat(columnExists(conn, "app_user", col))
                        .as("app_user.%s should exist", col).isTrue();
            }
        }
    }

    @Test
    @DisplayName("role_assignment table exists with required columns and CHECK constraint")
    void roleAssignment_tableAndColumnsExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(tableExists(conn, "role_assignment")).isTrue();
            for (String col : new String[]{"id","user_id","role_name","granted_at","granted_by"}) {
                assertThat(columnExists(conn, "role_assignment", col))
                        .as("role_assignment.%s should exist", col).isTrue();
            }
        }
    }

    @Test
    @DisplayName("refresh_token_family and refresh_token tables exist")
    void refreshTables_exist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(tableExists(conn, "refresh_token_family")).isTrue();
            assertThat(tableExists(conn, "refresh_token")).isTrue();
        }
    }

    @Test
    @DisplayName("role_assignment CHECK constraint rejects out-of-vocabulary role name")
    void roleAssignment_checkConstraintEnforced() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // First insert a valid user
            exec(conn, "INSERT INTO app_user (id, email, active, created_at, version) VALUES " +
                    "('ffffffff-0000-7000-8000-000000000001', 'chk@example.local', TRUE, NOW(), 0)");
            assertThatThrownBy(() -> exec(conn,
                    "INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES " +
                    "('ffffffff-0000-7000-8000-000000000010', " +
                    "'ffffffff-0000-7000-8000-000000000001', 'SUPER_ADMIN', NOW())"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("case-insensitive email unique index prevents duplicate case-varying registration")
    void email_caseInsensitiveUniqueness() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO app_user (id, email, active, created_at, version) VALUES " +
                    "('ffffffff-0000-7000-8000-000000000002', 'Alice@example.local', TRUE, NOW(), 0)");
            assertThatThrownBy(() -> exec(conn,
                    "INSERT INTO app_user (id, email, active, created_at, version) VALUES " +
                    "('ffffffff-0000-7000-8000-000000000003', 'alice@example.local', TRUE, NOW(), 0)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ---- AC-4: Envers revision creation -----------------------------------------

    @Test
    @DisplayName("persisting then updating AppUser produces exactly two audit revisions")
    void appUser_twoEnversRevisions() {
        setJwtAuth("audit-actor", "ROLE_ADMIN");
        TransactionTemplate tx = new TransactionTemplate(txManager);

        UUID userId = tx.execute(status -> {
            AppUser u = AppUser.createWithPassword(
                    "rev-test@example.local", "Rev User",
                    "$2a$10$" + "A".repeat(53));
            entityManager.persist(u);
            return u.getId();
        });

        tx.execute(status -> {
            AppUser u = entityManager.find(AppUser.class, userId);
            u.setDisplayName("Updated Name");
            return null;
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(AppUser.class, userId);
            assertThat(revs).hasSize(2);
            Object rev0 = reader.findRevision(AppRevision.class, revs.get(0));
            assertThat(rev0).isInstanceOf(AppRevision.class);
            assertThat(((AppRevision) rev0).getActorUserId()).isEqualTo("audit-actor");
            return null;
        });

        SecurityContextHolder.clearContext();
    }

    // ---- AC-5: Audit-table privilege (role_assignment_aud) ----------------------

    @Test
    @DisplayName("runtime role cannot UPDATE role_assignment_aud — immutability enforced by DB")
    void roleAssignmentAud_cannotBeUpdatedByRuntimeRole() throws SQLException {
        try (Connection adminConn = dataSource.getConnection()) {
            try (Statement st = adminConn.createStatement()) {
                st.execute("CREATE USER ra_audit_test WITH PASSWORD 'ra_pw_1'");
                st.execute("GRANT fieldservice TO ra_audit_test");
                st.execute("GRANT CONNECT ON DATABASE fsapi_identity_test TO ra_audit_test");
            }
        }

        String url = postgres.getJdbcUrl();
        try (Connection userConn = DriverManager.getConnection(url, "ra_audit_test", "ra_pw_1")) {
            assertThatThrownBy(() -> {
                try (Statement st = userConn.createStatement()) {
                    st.execute("UPDATE role_assignment_aud SET role_name = 'ADMIN' WHERE 1=0");
                }
            }).isInstanceOf(SQLException.class)
              .hasMessageContaining("permission denied");
        }
    }

    // ---- AC-6: Credential round-trip test ----------------------------------------

    @Test
    @DisplayName("60-char BCrypt hash survives a DB round-trip without truncation")
    void bcryptHash_roundTrip_noTruncation() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // A real BCrypt hash is exactly 60 chars: $2a$NN$<22-char-salt><31-char-hash>
        String bcryptHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        assertThat(bcryptHash).hasSize(60);

        // An algorithm-prefixed variant: algorithm prefix + BCrypt = 68 chars
        String prefixedHash = "{bcrypt}" + bcryptHash;
        assertThat(prefixedHash).hasSize(68);

        UUID id1 = tx.execute(status -> {
            AppUser u = AppUser.createWithPassword("bcrypt1@example.local", "BCrypt Test 1", bcryptHash);
            entityManager.persist(u);
            return u.getId();
        });

        UUID id2 = tx.execute(status -> {
            AppUser u = AppUser.createWithPassword("bcrypt2@example.local", "BCrypt Test 2", prefixedHash);
            entityManager.persist(u);
            return u.getId();
        });

        tx.execute(status -> {
            entityManager.clear();
            AppUser u1 = entityManager.find(AppUser.class, id1);
            AppUser u2 = entityManager.find(AppUser.class, id2);
            assertThat(u1.getPasswordHash()).isEqualTo(bcryptHash);
            assertThat(u2.getPasswordHash()).isEqualTo(prefixedHash);
            return null;
        });
    }

    // ---- AC-7: FK restriction (delete with grants) --------------------------------

    @Test
    @DisplayName("deleting app_user with active role_assignment rows is refused by FK")
    void appUser_fkRestrict_cannotDeleteWithGrants() throws SQLException {
        String userId = "ffffffff-0000-7000-8000-000000000004";
        String raId   = "ffffffff-0000-7000-8000-000000000040";
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO app_user (id, email, active, created_at, version) VALUES " +
                    "('" + userId + "', 'fktest@example.local', TRUE, NOW(), 0)");
            exec(conn, "INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES " +
                    "('" + raId + "', '" + userId + "', 'ADMIN', NOW())");

            assertThatThrownBy(() -> exec(conn,
                    "DELETE FROM app_user WHERE id = '" + userId + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ---- Helpers ----------------------------------------------------------------

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND LOWER(table_name) = LOWER(?)")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) > 0; }
        }
    }

    private static boolean columnExists(Connection conn, String tableName, String colName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND LOWER(table_name) = LOWER(?) " +
                "AND LOWER(column_name) = LOWER(?)")) {
            ps.setString(1, tableName);
            ps.setString(2, colName);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) > 0; }
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.executeUpdate(); }
    }

    private static void setJwtAuth(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        List<SimpleGrantedAuthority> auths = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, auths));
    }
}
