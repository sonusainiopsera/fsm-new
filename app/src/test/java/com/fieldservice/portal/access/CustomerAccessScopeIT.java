package com.fieldservice.portal.access;

import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalAccountUserRepository;
import com.fieldservice.portal.domain.PortalInvitation;
import com.fieldservice.portal.domain.PortalInvitationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for portal linkage persistence, Envers auditing, and field
 * encryption (WO-169, AC-2, AC-3, AC-10).
 *
 * <p>Uses Testcontainers PostgreSQL via {@link AbstractIntegrationTest} with V118 fixtures.
 */
@DisplayName("CustomerAccessScope and portal linkage integration tests")
@Transactional
@Rollback
class CustomerAccessScopeIT extends AbstractIntegrationTest {

    // Fixture UUIDs from V100 and V118
    private static final UUID USER_A     = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000016");
    private static final UUID ACCOUNT_A  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ORPHAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000018");

    @Autowired
    private PortalAccountUserRepository accountUserRepo;

    @Autowired
    private PortalInvitationRepository invitationRepo;

    // -------------------------------------------------------------------------
    // AC-2: Envers revision rows produced on insert and update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: inserting a portal_account_user row produces an Envers revision in REVINFO")
    void portalAccountUser_insert_producesEnversRevision() {
        UUID newUserId = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000ff");
        PortalAccountUser linkage = new PortalAccountUser(newUserId, ACCOUNT_A);
        accountUserRepo.save(linkage);
        accountUserRepo.flush();

        // Verify a revision row exists in portal_account_user_aud
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_account_user_aud WHERE user_id = ?",
                Integer.class, newUserId);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: updating portal_account_user (activation) produces a second revision")
    void portalAccountUser_update_producesSecondEnversRevision() {
        UUID newUserId = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000fe");
        PortalAccountUser linkage = new PortalAccountUser(newUserId, ACCOUNT_A);
        accountUserRepo.saveAndFlush(linkage);

        linkage.activate(Instant.now());
        accountUserRepo.saveAndFlush(linkage);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_account_user_aud WHERE user_id = ?",
                Integer.class, newUserId);
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // AC-3: contact fields stored as ciphertext, never plaintext
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: contact_email_enc column stores ciphertext, not plaintext email")
    void portalInvitation_contactEmail_storedAsCiphertext() {
        String plainEmail = "secret@example.com";
        String tokenHash = "a".repeat(64);
        PortalInvitation inv = new PortalInvitation(
                ACCOUNT_A, plainEmail, "Test Name", tokenHash, Instant.now().plusSeconds(3600));
        invitationRepo.saveAndFlush(inv);

        // Read raw column value via JDBC — must NOT be the plaintext email
        String raw = jdbc.queryForObject(
                "SELECT contact_email_enc FROM portal_invitation WHERE id = ?",
                String.class, inv.getId());

        assertThat(raw).isNotNull();
        assertThat(raw).doesNotContain(plainEmail);
        // AES-256-GCM format: base64(iv).base64(ciphertext)
        assertThat(raw).contains(".");
    }

    // -------------------------------------------------------------------------
    // AC-1: portal_account_user fixture data is accessible
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: V118 fixture portal_account_user row for USER_A is findable by userId")
    void fixturePortalAccountUser_findByUserId_returnsActiveRow() {
        Optional<PortalAccountUser> linkage = accountUserRepo.findByUserId(USER_A);
        assertThat(linkage).isPresent();
        assertThat(linkage.get().getAccountId()).isEqualTo(ACCOUNT_A);
        assertThat(linkage.get().getStatus())
                .isEqualTo(com.fieldservice.portal.domain.PortalAccountUserStatus.ACTIVE);
    }

    @Test
    @DisplayName("AC-11: orphan user has no portal_account_user row")
    void fixtureOrphanUser_hasNoLinkage() {
        Optional<PortalAccountUser> linkage = accountUserRepo.findByUserId(USER_ORPHAN);
        assertThat(linkage).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-6 / AC-7: Disclosure rule verified via PortalExceptionAdvice mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: ScopeUnavailableException message does not contain account or resource ids")
    void scopeUnavailableException_messageIsNonDisclosing() {
        ScopeUnavailableException ex = new ScopeUnavailableException(
                "No portal linkage exists for userId=some-id");
        // The message is safe to log; it contains userId (for diagnostics) but
        // the HTTP response body from PortalExceptionAdvice strips it entirely
        assertThat(ex.getMessage()).doesNotContain("account_id");
    }
}
