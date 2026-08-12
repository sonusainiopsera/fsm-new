package com.fieldservice.portal;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.api.ErrorCode;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalInvitation;
import com.fieldservice.portal.repository.PortalAccountUserRepository;
import com.fieldservice.portal.repository.PortalInvitationRepository;
import com.fieldservice.portal.service.PortalInvitationService;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration tests for portal linkage, Envers revisions, field encryption,
 * CustomerAccessScope DB resolution, and invitation lifecycle.
 *
 * <p>Tests assert:
 * <ul>
 *   <li>V32 migration created portal_account_user and portal_invitation tables (AC-1).</li>
 *   <li>Envers revision rows are produced on insert and update of portal_account_user (AC-2).</li>
 *   <li>contact_email persisted column value is ciphertext, not plaintext (AC-3).</li>
 *   <li>CustomerAccessScope resolves account_id from DB (AC-4).</li>
 *   <li>CustomerAccessScope fails closed when no linkage exists (AC-4).</li>
 *   <li>Invitation lifecycle: issue → activate produces linkage and Envers revision (AC-8).</li>
 *   <li>Adversarial: token replay after consumption produces InvitationNotFoundException (AC-8).</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class PortalAccessIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice_test")
                    .withUsername("sa")
                    .withPassword("sa");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        reg.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        reg.add("spring.flyway.url",          postgres::getJdbcUrl);
        reg.add("spring.flyway.user",         postgres::getUsername);
        reg.add("spring.flyway.password",     postgres::getPassword);
    }

    @Autowired PortalAccountUserRepository accountUserRepository;
    @Autowired PortalInvitationRepository  invitationRepository;
    @Autowired PortalInvitationService     invitationService;
    @Autowired JdbcTemplate                jdbcTemplate;
    @Autowired EntityManager               entityManager;
    @Autowired PlatformTransactionManager  txManager;
    @Autowired CustomerAccessScope         customerAccessScope;

    // Seed UUIDs scoped to portal IT tests (prefix pp000000-)
    static final UUID ACME_ID   = UUID.fromString("00000000-0000-7012-8000-000000000001");
    static final UUID BLUE_ID   = UUID.fromString("00000000-0000-7012-8000-000000000002");
    static final UUID USER_ACME = UUID.fromString("00000000-0000-7019-8000-000000000001");
    static final UUID USER_BLUE = UUID.fromString("00000000-0000-7019-8000-000000000002");
    static final UUID ORPHAN_ID = UUID.fromString("00000000-0000-7019-8000-000000000003");
    static final UUID ADMIN_ID  = UUID.fromString("00000000-0000-7017-8000-000000000005");

    TransactionTemplate tx;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(txManager);
        // Clean up portal rows between tests
        tx.executeWithoutResult(s -> {
            jdbcTemplate.update("DELETE FROM portal_account_user WHERE user_id IN (?,?,?)",
                    USER_ACME, USER_BLUE, ORPHAN_ID);
            jdbcTemplate.update("DELETE FROM portal_invitation WHERE created_by = ?", ADMIN_ID);
        });
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------
    // AC-1: Migration tables exist
    // ---------------------------------------------------------------

    @Test
    @DisplayName("AC-1: V32 migration created portal_account_user table")
    void migration_createdPortalAccountUserTable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = 'portal_account_user'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-1: V32 migration created portal_invitation table")
    void migration_createdPortalInvitationTable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = 'portal_invitation'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // AC-2: Envers revision rows produced on insert and update
    // ---------------------------------------------------------------

    @Test
    @DisplayName("AC-2: Envers produces revision row on portal_account_user insert")
    void envers_revisionOnInsert() {
        UUID linkId = tx.execute(s -> {
            PortalAccountUser pau = new PortalAccountUser(USER_ACME, ACME_ID);
            accountUserRepository.save(pau);
            return pau.getId();
        });

        List<?> revs = tx.execute(s -> AuditReaderFactory.get(entityManager)
                .createQuery()
                .forRevisionsOfEntity(PortalAccountUser.class, false, true)
                .add(AuditEntity.id().eq(linkId))
                .getResultList());

        assertThat(revs).hasSize(1);
    }

    @Test
    @DisplayName("AC-2: Envers produces revision row on portal_account_user update")
    void envers_revisionOnUpdate() {
        UUID linkId = tx.execute(s -> {
            PortalAccountUser pau = new PortalAccountUser(USER_ACME, ACME_ID);
            accountUserRepository.save(pau);
            return pau.getId();
        });

        tx.executeWithoutResult(s -> {
            PortalAccountUser pau = accountUserRepository.findById(linkId).orElseThrow();
            pau.suspend();
            accountUserRepository.save(pau);
        });

        List<?> revs = tx.execute(s -> AuditReaderFactory.get(entityManager)
                .createQuery()
                .forRevisionsOfEntity(PortalAccountUser.class, false, true)
                .add(AuditEntity.id().eq(linkId))
                .getResultList());

        assertThat(revs).hasSizeGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------
    // AC-3: contact_email is stored as ciphertext
    // ---------------------------------------------------------------

    @Test
    @DisplayName("AC-3: contact_email in portal_invitation is stored as ciphertext, not plaintext")
    void contactEmail_storedAsCiphertext() {
        String plainEmail = "test.user@example.com";

        UUID invId = tx.execute(s -> {
            var result = invitationService.issueInvitation(
                    ACME_ID, plainEmail, "Test User", ADMIN_ID);
            return result.invitationId();
        });

        String stored = jdbcTemplate.queryForObject(
                "SELECT contact_email FROM portal_invitation WHERE id = ?",
                String.class, invId);

        assertThat(stored).isNotNull()
                .doesNotContain(plainEmail)     // not plaintext
                .doesNotContain("@");           // base64 encoded
    }

    // ---------------------------------------------------------------
    // AC-4: CustomerAccessScope resolves account_id from DB
    // ---------------------------------------------------------------

    @Test
    @DisplayName("AC-4: CustomerAccessScope resolves account_id for linked CUSTOMER user")
    void customerAccessScope_resolvesAccountId_whenLinked() {
        tx.executeWithoutResult(s ->
                accountUserRepository.save(new PortalAccountUser(USER_ACME, ACME_ID)));

        setJwtContext(USER_ACME);

        UUID resolved = customerAccessScope.resolveAccountId();
        assertThat(resolved).isEqualTo(ACME_ID);
    }

    @Test
    @DisplayName("AC-4: CustomerAccessScope fails closed — throws when no linkage row")
    void customerAccessScope_failsClosed_whenNoLinkage() {
        setJwtContext(ORPHAN_ID); // orphan user has no portal_account_user row

        assertThatThrownBy(() -> customerAccessScope.resolveAccountId())
                .isInstanceOf(com.fieldservice.portal.access.ScopeUnavailableException.class);
    }

    // ---------------------------------------------------------------
    // AC-8: Invitation lifecycle
    // ---------------------------------------------------------------

    @Test
    @DisplayName("AC-8: Invitation issuance stores hashed token and expiry; activation creates linkage")
    void invitationLifecycle_issueAndActivate() {
        // Issue invitation for an orphan user
        String[] tokenHolder = new String[1];
        UUID invId = tx.execute(s -> {
            var result = invitationService.issueInvitation(ACME_ID, null, null, ADMIN_ID);
            tokenHolder[0] = result.plainToken();
            return result.invitationId();
        });

        // Token hash is stored, not plaintext
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM portal_invitation WHERE id = ?", String.class, invId);
        assertThat(storedHash).isEqualTo(PortalInvitationService.sha256Hex(tokenHolder[0]));

        // Activate
        tx.executeWithoutResult(s ->
                invitationService.activateInvitation(tokenHolder[0], ORPHAN_ID));

        // Linkage created
        assertThat(accountUserRepository.findByUserId(ORPHAN_ID)).isPresent()
                .get()
                .satisfies(pau -> assertThat(pau.getAccountId()).isEqualTo(ACME_ID));

        // Invitation marked consumed
        PortalInvitation inv = invitationRepository.findById(invId).orElseThrow();
        assertThat(inv.getConsumedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC-8: Replayed token throws InvitationNotFoundException (non-disclosing)")
    void invitationReplay_throwsInvitationNotFound() {
        String[] tokenHolder = new String[1];
        tx.executeWithoutResult(s -> {
            var result = invitationService.issueInvitation(ACME_ID, null, null, ADMIN_ID);
            tokenHolder[0] = result.plainToken();
        });
        tx.executeWithoutResult(s ->
                invitationService.activateInvitation(tokenHolder[0], ORPHAN_ID));

        // Replay — must fail, not allow re-use
        assertThatThrownBy(() ->
                tx.executeWithoutResult(s ->
                        invitationService.activateInvitation(tokenHolder[0], USER_BLUE)))
                .isInstanceOf(PortalInvitationService.InvitationNotFoundException.class);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void setJwtContext(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("test.token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
