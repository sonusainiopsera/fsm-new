package com.fieldservice.app.identity;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.appuser.AppUser;
import com.fieldservice.domain.appuser.AppUserRepository;
import com.fieldservice.domain.identity.AppRole;
import com.fieldservice.domain.identity.RoleAssignment;
import com.fieldservice.domain.identity.RoleAssignmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies WO-108 acceptance criteria:
 *  AC-1:  app_user schema shape (nullable password_hash, width, case-insensitive email index)
 *  AC-2:  role_assignment schema (CHECK constraint, UNIQUE, ON DELETE RESTRICT)
 *  AC-3:  refresh_token_family and refresh_token schema
 *  AC-4:  Envers auditing — insert + update yields exactly two audit rows
 *  AC-5:  Grant matrix — runtime role cannot UPDATE app_user_aud
 *  AC-6:  BCrypt hash round-trip (no silent truncation)
 *  AC-7:  ON DELETE RESTRICT prevents user deletion when grants exist
 *  AC-8:  Migration idempotency
 *  AC-11: Identity fixtures provide one user per role, one inactive, one no-role
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("WO-108 Identity migration schema tests")
class IdentityMigrationSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    // ── AC-1: app_user schema ──────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("AC-1: identity tables exist")
    void identity_tables_exist() {
        for (String table : new String[]{"role_assignment", "refresh_token_family", "refresh_token"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("Table '%s' must exist", table).isEqualTo(1);
        }
    }

    @Test
    @Order(2)
    @DisplayName("AC-1: password_hash is nullable and at least 72 characters wide")
    void password_hash_nullable_and_wide_enough() {
        String isNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'app_user' AND column_name = 'password_hash'",
                String.class);
        assertThat(isNullable).as("password_hash must be nullable").isEqualToIgnoringCase("YES");

        Integer maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_name = 'app_user' AND column_name = 'password_hash'",
                Integer.class);
        assertThat(maxLen).as("password_hash must be at least 72 chars wide")
                .isGreaterThanOrEqualTo(72);
    }

    @Test
    @Order(3)
    @DisplayName("AC-1: case-insensitive email unique index exists on lower(email)")
    void case_insensitive_email_index_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes " +
                "WHERE tablename = 'app_user' AND indexname = 'uq_app_user_email_ci'",
                Integer.class);
        assertThat(count).as("uq_app_user_email_ci index must exist").isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("AC-1: display_name and external_subject columns exist on app_user")
    void app_user_has_new_columns() {
        for (String col : new String[]{"display_name", "external_subject"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = 'app_user' AND column_name = ?",
                    Integer.class, col);
            assertThat(count).as("Column '%s' must exist on app_user", col).isEqualTo(1);
        }
    }

    // ── AC-2: role_assignment schema ───────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("AC-2: role_assignment has CHECK constraint on role_name")
    void role_assignment_check_constraint_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c " +
                "JOIN pg_class r ON r.oid = c.conrelid " +
                "WHERE r.relname = 'role_assignment' AND c.contype = 'c' " +
                "AND c.conname LIKE '%role_name%'",
                Integer.class);
        assertThat(count).as("role_assignment CHECK constraint on role_name must exist")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("AC-2: INSERT with out-of-vocabulary role_name is rejected")
    void out_of_vocabulary_role_name_is_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO role_assignment (id, user_id, role_name, granted_at) " +
                "VALUES (gen_random_uuid(), " +
                "(SELECT id FROM app_user LIMIT 1), 'SUPERUSER', now())"))
                .hasMessageContaining("chk_role_name");
    }

    @Test
    @Order(7)
    @DisplayName("AC-2: UNIQUE(user_id, role_name) constraint exists on role_assignment")
    void role_assignment_unique_constraint_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint c " +
                "JOIN pg_class r ON r.oid = c.conrelid " +
                "WHERE r.relname = 'role_assignment' AND c.contype = 'u' " +
                "AND c.conname LIKE '%uq_role_assignment%'",
                Integer.class);
        assertThat(count).as("UNIQUE(user_id, role_name) must exist on role_assignment")
                .isGreaterThanOrEqualTo(1);
    }

    // ── AC-3: refresh_token schema ─────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("AC-3: refresh_token.token_hash is exactly 64 characters wide")
    void token_hash_column_is_64_chars() {
        Integer maxLen = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_name = 'refresh_token' AND column_name = 'token_hash'",
                Integer.class);
        assertThat(maxLen).as("token_hash must hold 64-char SHA-256 hex").isEqualTo(64);
    }

    @Test
    @Order(9)
    @DisplayName("AC-3: refresh_token indexes exist (token_hash, family_id, expires_at)")
    void refresh_token_indexes_exist() {
        for (String idx : new String[]{
                "uq_refresh_token_hash", "idx_refresh_token_family_id", "idx_refresh_token_expires_at"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?",
                    Integer.class, idx);
            assertThat(count).as("Index '%s' must exist", idx).isEqualTo(1);
        }
    }

    // ── AC-4: Envers auditing ──────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("AC-4: role_assignment_aud table exists")
    void role_assignment_aud_table_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = 'role_assignment_aud'",
                Integer.class);
        assertThat(count).as("role_assignment_aud must exist").isEqualTo(1);
    }

    @Test
    @Order(11)
    @DisplayName("AC-4: persisting and updating AppUser produces two audit rows in app_user_aud")
    void app_user_insert_and_update_produces_two_audit_rows() {
        setAdminAuth("identity-audit-test");

        AtomicReference<UUID> userIdRef = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Insert
        tx.executeWithoutResult(status -> {
            AppUser user = new AppUser(
                    "audit-test-" + System.nanoTime() + "@identity.test",
                    "$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    "Audit Test User");
            em.persist(user);
            userIdRef.set(user.getId());
        });

        // Update
        tx.executeWithoutResult(status -> {
            AppUser user = em.find(AppUser.class, userIdRef.get());
            user.setDisplayName("Updated Name");
        });

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_aud WHERE id = ?",
                Integer.class, userIdRef.get());
        assertThat(auditCount).as("Two audit rows expected: ADD and MOD").isEqualTo(2);
    }

    @Test
    @Order(12)
    @DisplayName("AC-4: persisting RoleAssignment creates an audit row in role_assignment_aud")
    void role_assignment_persist_creates_audit_row() {
        setAdminAuth("identity-audit-role-test");

        AtomicReference<UUID> assignIdRef = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        AtomicReference<UUID> userIdRef = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            AppUser user = new AppUser(
                    "role-audit-" + System.nanoTime() + "@identity.test",
                    "$2a$10$BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                    "Role Audit User");
            em.persist(user);
            userIdRef.set(user.getId());
        });

        tx.executeWithoutResult(status -> {
            AppUser user = em.find(AppUser.class, userIdRef.get());
            RoleAssignment ra = new RoleAssignment(user, AppRole.DISPATCHER, null);
            em.persist(ra);
            assignIdRef.set(ra.getId());
        });

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment_aud WHERE id = ?",
                Integer.class, assignIdRef.get());
        assertThat(auditCount).as("One audit row expected for RoleAssignment insert").isEqualTo(1);
    }

    // ── AC-5: grant matrix — runtime role cannot UPDATE ────────────────────

    @Test
    @Order(13)
    @DisplayName("AC-5: fieldservice_runtime role cannot UPDATE app_user_aud rows")
    void runtime_role_cannot_update_app_user_aud() {
        jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT FROM pg_user WHERE usename = 'fs_id_rt_test') THEN
                        CREATE USER fs_id_rt_test WITH PASSWORD 'test';
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT fieldservice_runtime TO fs_id_rt_test");

        DriverManagerDataSource runtimeDs = new DriverManagerDataSource();
        runtimeDs.setUrl(datasourceUrl);
        runtimeDs.setUsername("fs_id_rt_test");
        runtimeDs.setPassword("test");

        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDs);

        assertThatThrownBy(() ->
                runtimeJdbc.execute("UPDATE app_user_aud SET revtype = 0 WHERE 1=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @Order(14)
    @DisplayName("AC-5: fieldservice_runtime role cannot UPDATE role_assignment_aud rows")
    void runtime_role_cannot_update_role_assignment_aud() {
        DriverManagerDataSource runtimeDs = new DriverManagerDataSource();
        runtimeDs.setUrl(datasourceUrl);
        runtimeDs.setUsername("fs_id_rt_test");
        runtimeDs.setPassword("test");

        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDs);

        assertThatThrownBy(() ->
                runtimeJdbc.execute("UPDATE role_assignment_aud SET revtype = 0 WHERE 1=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ── AC-6: BCrypt hash round-trip ───────────────────────────────────────

    @Test
    @Order(15)
    @DisplayName("AC-6: 60-char BCrypt hash is stored and retrieved with no truncation")
    void bcrypt_hash_roundtrip_no_truncation() {
        // Real BCrypt output format: $2a$10$ + 53 chars = 60 chars total
        String bcrypt60 = "$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertThat(bcrypt60).hasSize(60);

        // Algorithm-prefixed variant: {bcrypt}$2a$10$...
        String prefixed = "{bcrypt}" + bcrypt60;
        assertThat(prefixed).hasSize(68); // well within 72

        TransactionTemplate tx = new TransactionTemplate(txManager);
        AtomicReference<UUID> id1Ref = new AtomicReference<>();
        AtomicReference<UUID> id2Ref = new AtomicReference<>();

        tx.executeWithoutResult(status -> {
            AppUser u1 = new AppUser(
                    "bcrypt60-" + System.nanoTime() + "@identity.test", bcrypt60, "BCrypt60 User");
            AppUser u2 = new AppUser(
                    "bcryptpfx-" + System.nanoTime() + "@identity.test", prefixed, "BCrypt Pfx User");
            em.persist(u1);
            em.persist(u2);
            id1Ref.set(u1.getId());
            id2Ref.set(u2.getId());
        });

        String retrieved60 = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?", String.class, id1Ref.get());
        String retrievedPfx = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?", String.class, id2Ref.get());

        assertThat(retrieved60).as("60-char BCrypt hash must round-trip unchanged").isEqualTo(bcrypt60);
        assertThat(retrievedPfx).as("Prefixed BCrypt hash must round-trip unchanged").isEqualTo(prefixed);
    }

    // ── AC-7: ON DELETE RESTRICT for role_assignment ───────────────────────

    @Test
    @Order(16)
    @DisplayName("AC-7: deleting an app_user with existing role_assignment is refused (ON DELETE RESTRICT)")
    void delete_user_with_role_assignment_is_restricted() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        AtomicReference<UUID> userIdRef = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            AppUser user = new AppUser(
                    "restrict-" + System.nanoTime() + "@identity.test",
                    null, "Restrict Test User");
            em.persist(user);
            userIdRef.set(user.getId());
        });

        // Grant a role to the user
        tx.executeWithoutResult(status -> {
            AppUser user = em.find(AppUser.class, userIdRef.get());
            RoleAssignment ra = new RoleAssignment(user, AppRole.TECHNICIAN, null);
            em.persist(ra);
        });

        // Attempting to delete the user should fail due to FK RESTRICT on role_assignment
        assertThatThrownBy(() ->
                tx.executeWithoutResult(status ->
                        jdbc.update("DELETE FROM app_user WHERE id = ?", userIdRef.get())))
                .hasMessageContaining("violates foreign key constraint");
    }

    // ── AC-8: migration idempotency ────────────────────────────────────────

    @Test
    @Order(17)
    @DisplayName("AC-8: Flyway migrate is idempotent — no new migrations on second run")
    void flyway_rerun_produces_no_new_migrations() {
        // Flyway ran on startup; no additional migrations should execute
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(datasourceUrl, "test", "test")
                .locations("classpath:db/migration", "classpath:db/testfixtures")
                .load();
        int executed = flyway.migrate().migrationsExecuted;
        assertThat(executed).as("Re-running Flyway should produce 0 new migrations").isZero();
    }

    // ── AC-11: identity fixtures ───────────────────────────────────────────

    @Test
    @Order(18)
    @DisplayName("AC-11: one active fixture user exists per ratified role")
    void identity_fixtures_have_one_user_per_role() {
        for (String role : new String[]{"ADMIN", "DISPATCHER", "TECHNICIAN", "MANAGER", "CUSTOMER"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role_assignment ra " +
                    "JOIN app_user u ON u.id = ra.user_id " +
                    "WHERE ra.role_name = ? AND u.is_active = TRUE",
                    Integer.class, role);
            assertThat(count).as("Fixture must have an active user with role %s", role)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @Order(19)
    @DisplayName("AC-11: fixture includes one inactive user")
    void identity_fixtures_include_inactive_user() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE is_active = FALSE AND email LIKE '%identity.test%'",
                Integer.class);
        assertThat(count).as("Fixture must include at least one inactive user").isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(20)
    @DisplayName("AC-11: fixture includes user with no role grants")
    void identity_fixtures_include_user_with_no_grants() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user u " +
                "WHERE NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id) " +
                "AND u.email LIKE '%identity.test%'",
                Integer.class);
        assertThat(count).as("Fixture must include at least one user with no role grants")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(21)
    @DisplayName("AC-11: case-insensitive email lookup finds fixture user regardless of case")
    void email_lookup_is_case_insensitive() {
        // The case-insensitive index must make admin@identity.test == ADMIN@IDENTITY.TEST
        assertThat(appUserRepository.findByEmailIgnoreCase("ADMIN@IDENTITY.TEST")).isPresent();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setAdminAuth(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
