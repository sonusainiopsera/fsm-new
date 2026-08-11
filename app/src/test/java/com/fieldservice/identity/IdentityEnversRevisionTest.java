package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.identity.domain.RoleAssignmentRepository;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Envers audit on AppUser and RoleAssignment (WO-108).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Saving then updating an AppUser yields exactly two audit rows in app_user_aud.</li>
 *   <li>Both audit rows carry the acting actor identifier from the security context.</li>
 *   <li>password_hash is absent from app_user_aud (CONFIDENTIAL / @NotAudited).</li>
 *   <li>external_subject is absent from app_user_aud (@NotAudited).</li>
 *   <li>Saving a RoleAssignment yields one audit row in role_assignment_aud.</li>
 *   <li>A background job with no principal records SYSTEM actor in REVINFO.</li>
 * </ul>
 */
@DisplayName("Identity Envers revision tests")
class IdentityEnversRevisionTest extends AbstractIntegrationTest {

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;
    @Autowired RoleAssignmentRepository roleAssignmentRepository;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAdminPrincipal() {
        var auth = new UsernamePasswordAuthenticationToken(
                TestJwtFactory.MANAGER_USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // -------------------------------------------------------------------------
    // AppUser — two revisions on insert + update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Saving and updating AppUser yields two audit revisions with actor attribution")
    void appUserInsertThenUpdate_yieldsTwoRevisions() {
        setAdminPrincipal();

        // TX1: insert
        UUID[] idHolder = {null};
        txTemplate.executeWithoutResult(status -> {
            AppUser u = new AppUser();
            u.setEmail("rev.test." + UUID.randomUUID() + "@example.com");
            u.setPasswordHash("$2a$10$placeholder.test.hash...........ok");
            u.setDisplayName("Revision Test User");
            entityManager.persist(u);
            entityManager.flush();
            idHolder[0] = u.getId();
        });

        // TX2: update
        txTemplate.executeWithoutResult(status -> {
            AppUser u = entityManager.find(AppUser.class, idHolder[0]);
            u.setDisplayName("Revision Test User Updated");
            entityManager.flush();
        });

        // Assert exactly two audit revisions
        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(AppUser.class, idHolder[0]);
            assertThat(revs).hasSize(2);

            // Verify actor attribution on both revisions
            for (Number rev : revs) {
                AuditRevisionEntity revInfo = reader.findRevision(AuditRevisionEntity.class, rev);
                assertThat(revInfo.getActorUserId())
                        .as("Actor user id must be propagated to REVINFO rev=%d", rev.intValue())
                        .isEqualTo(TestJwtFactory.MANAGER_USER_ID.toString());
            }
        });
    }

    @Test
    @DisplayName("password_hash is absent from app_user_aud (CONFIDENTIAL @NotAudited)")
    void passwordHashAbsentFromAuditTable() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT column_name FROM information_schema.columns " +
                     "WHERE table_schema='public' AND table_name='app_user_aud' AND column_name='password_hash'")) {
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("password_hash must NOT appear in app_user_aud")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("external_subject is absent from app_user_aud (@NotAudited)")
    void externalSubjectAbsentFromAuditTable() throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT column_name FROM information_schema.columns " +
                     "WHERE table_schema='public' AND table_name='app_user_aud' AND column_name='external_subject'")) {
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("external_subject must NOT appear in app_user_aud (deferred PII)")
                        .isFalse();
            }
        }
    }

    // -------------------------------------------------------------------------
    // RoleAssignment — one revision on insert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Saving a RoleAssignment yields one audit revision in role_assignment_aud")
    void roleAssignmentInsert_yieldsOneRevision() {
        setAdminPrincipal();

        // Use an existing user from V100 fixtures
        UUID userId = TestJwtFactory.DISPATCHER_USER_ID;

        UUID[] raId = {null};
        txTemplate.executeWithoutResult(status -> {
            RoleAssignment ra = new RoleAssignment(userId, IdentityRole.MANAGER, Instant.now(), null);
            RoleAssignment saved = roleAssignmentRepository.save(ra);
            raId[0] = saved.getId();
        });

        try {
            txTemplate.executeWithoutResult(status -> {
                AuditReader reader = AuditReaderFactory.get(entityManager);
                List<Number> revs = reader.getRevisions(RoleAssignment.class, raId[0]);
                assertThat(revs)
                        .as("One audit revision expected for insert")
                        .hasSize(1);

                AuditRevisionEntity revInfo = reader.findRevision(AuditRevisionEntity.class, revs.get(0));
                assertThat(revInfo.getActorUserId())
                        .isEqualTo(TestJwtFactory.MANAGER_USER_ID.toString());
            });
        } finally {
            // Clean up — delete the test grant to not pollute other tests
            txTemplate.executeWithoutResult(status ->
                    roleAssignmentRepository.findById(raId[0]).ifPresent(roleAssignmentRepository::delete));
        }
    }

    // -------------------------------------------------------------------------
    // No authenticated principal → SYSTEM actor
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No authenticated principal records SYSTEM actor in REVINFO")
    void noAuthPrincipal_recordsSystemActor() {
        // Clear security context — simulates a worker/background job
        SecurityContextHolder.clearContext();

        UUID[] idHolder = {null};
        txTemplate.executeWithoutResult(status -> {
            AppUser u = new AppUser();
            u.setEmail("sys.actor." + UUID.randomUUID() + "@example.com");
            u.setDisplayName("System Actor Test");
            entityManager.persist(u);
            entityManager.flush();
            idHolder[0] = u.getId();
        });

        txTemplate.executeWithoutResult(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(AppUser.class, idHolder[0]);
            assertThat(revs).isNotEmpty();
            AuditRevisionEntity revInfo = reader.findRevision(AuditRevisionEntity.class, revs.get(0));
            assertThat(revInfo.getActorUserId())
                    .as("No-principal mutations must record system actor (lowercase), not null")
                    .isEqualTo("system");
        });
    }

    @Autowired
    private javax.sql.DataSource dataSource;
}
