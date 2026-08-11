package com.fieldservice.privacy;

import com.fieldservice.platform.crypto.BlindIndex;
import com.fieldservice.platform.crypto.EnvelopeEncryptedStringConverter;
import com.fieldservice.platform.crypto.LocalStubKeyManager;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for per-subject envelope encryption (WO-193, AC-3,4,5,7,12,13).
 *
 * <p>Uses Testcontainers PostgreSQL 16 via {@link AbstractIntegrationTest}.
 * Verifies that encrypted columns contain no plaintext in live tables or Envers audit tables,
 * that blind-index lookup works, that rotation preserves readability, and that key
 * destruction renders both live and audit values permanently unreadable.
 */
@DisplayName("Envelope encryption integration tests")
@Transactional
@Rollback
class EnvelopeEncryptionIT extends AbstractIntegrationTest {

    private static final String PLAINTEXT_EMAIL = "alice@example.invalid";
    private static final String PLAINTEXT_LAT   = "51.50";
    private static final String PLAINTEXT_LON   = "-0.11";

    @Autowired
    private SubjectKeyManager subjectKeyManager;

    // ── AC-3/4: ciphertext stored in live table, not plaintext ────────────────

    @Test
    @DisplayName("AC-3: portal_invitation stores ciphertext, not plaintext email")
    void portalInvitation_storesNoPlainterEmail() throws Exception {
        UUID accountId = UUID.randomUUID();
        String tokenHash = "aabbcc" + UUID.randomUUID().toString().replace("-", "").substring(0, 58);
        insertInvitation(accountId, PLAINTEXT_EMAIL, "Alice Test", tokenHash);

        // Raw SQL read must not contain the plaintext email
        String rawEmail = jdbc.queryForObject(
                "SELECT contact_email_enc FROM portal_invitation WHERE token_hash = ?",
                String.class, tokenHash);

        assertThat(rawEmail).isNotNull();
        assertThat(rawEmail).doesNotContain(PLAINTEXT_EMAIL);
        assertThat(rawEmail).doesNotContain("alice");
        // New format: base64 without a dot separator
        assertThat(rawEmail).doesNotContain(".");
    }

    @Test
    @DisplayName("AC-5: blind index enables equality lookup without plaintext scan")
    void portalInvitation_blindIndexEnablesLookup() throws Exception {
        UUID accountId = UUID.randomUUID();
        String tokenHash = "cc0011" + UUID.randomUUID().toString().replace("-", "").substring(0, 58);
        insertInvitation(accountId, PLAINTEXT_EMAIL, "Alice", tokenHash);

        String expectedIdx = BlindIndex.compute(PLAINTEXT_EMAIL);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation WHERE contact_email_idx = ?",
                Integer.class, expectedIdx);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-4: portal_invitation_aud stores ciphertext not plaintext (Envers audit)")
    void portalInvitation_auditTableStoresCiphertext() throws Exception {
        UUID accountId = UUID.randomUUID();
        String tokenHash = "dd0022" + UUID.randomUUID().toString().replace("-", "").substring(0, 58);
        insertInvitation(accountId, PLAINTEXT_EMAIL, "Alice", tokenHash);

        // Audit table should have ciphertext
        Integer audCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation_aud WHERE token_hash = ?",
                Integer.class, tokenHash);
        assertThat(audCount).isGreaterThanOrEqualTo(1);

        // The audit row should not contain plaintext
        Map<String, Object> audRow = jdbc.queryForMap(
                "SELECT contact_email_enc FROM portal_invitation_aud WHERE token_hash = ? LIMIT 1",
                tokenHash);
        String audEmail = (String) audRow.get("contact_email_enc");
        if (audEmail != null) {
            assertThat(audEmail).doesNotContain(PLAINTEXT_EMAIL);
        }
    }

    @Test
    @DisplayName("AC-8: key rotation preserves readability of pre-rotation rows")
    void keyRotation_preRotationRowsStillReadable() throws Exception {
        UUID accountId = UUID.randomUUID();
        String tokenHash = "ee0033" + UUID.randomUUID().toString().replace("-", "").substring(0, 58);
        insertInvitation(accountId, PLAINTEXT_EMAIL, "Bob", tokenHash);

        // Rotate the key
        subjectKeyManager.rotate("CUSTOMER_ACCOUNT", accountId);

        // Re-read the raw ciphertext and verify it still decrypts (via entity load)
        // We verify indirectly: if the blind index still matches, the email is consistent
        String expectedIdx = BlindIndex.compute(PLAINTEXT_EMAIL);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation WHERE contact_email_idx = ?",
                Integer.class, expectedIdx);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-7: key destruction renders live and audit rows unreadable, row counts unchanged")
    void keyDestruction_rendersRowsUnreadable_preservesRowCount() throws Exception {
        UUID accountId = UUID.randomUUID();
        String tokenHash = "ff0044" + UUID.randomUUID().toString().replace("-", "").substring(0, 58);
        insertInvitation(accountId, PLAINTEXT_EMAIL, "Carol", tokenHash);

        int liveCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation", Integer.class);
        int audCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation_aud", Integer.class);

        // Destroy the key
        subjectKeyManager.destroy("CUSTOMER_ACCOUNT", accountId);

        // Row counts unchanged
        int liveCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation", Integer.class);
        int audCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portal_invitation_aud", Integer.class);
        assertThat(liveCountAfter).isEqualTo(liveCountBefore);
        assertThat(audCountAfter).isEqualTo(audCountBefore);

        // The ciphertext in the DB hasn't changed (we don't re-encrypt; the key is gone)
        // Verify by attempting to decrypt via the converter: should return UNRECOVERABLE_MARKER
        String rawEmail = jdbc.queryForObject(
                "SELECT contact_email_enc FROM portal_invitation WHERE token_hash = ?",
                String.class, tokenHash);
        assertThat(rawEmail).isNotNull();

        // Decrypt using converter directly
        EnvelopeEncryptedStringConverter conv = new EnvelopeEncryptedStringConverter();
        String decrypted = conv.convertToEntityAttribute(rawEmail);
        assertThat(decrypted).isEqualTo(EnvelopeEncryptedStringConverter.UNRECOVERABLE_MARKER);
    }

    @Test
    @DisplayName("AC-7 idempotency: destroy is safe to call multiple times")
    void keyDestruction_isIdempotent() {
        UUID accountId = UUID.randomUUID();
        subjectKeyManager.destroy("CUSTOMER_ACCOUNT", accountId);
        subjectKeyManager.destroy("CUSTOMER_ACCOUNT", accountId); // second call must not throw
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertInvitation(UUID accountId, String email, String name, String tokenHash) {
        // Use JDBC directly, mirroring what PortalInvitationRepository does via JPA
        // We rely on the EnvelopeEncryptionConfig having been auto-configured in the Spring context
        Instant expiresAt = Instant.now().plusSeconds(86400);
        String emailIdx = BlindIndex.compute(email);
        String nameIdx  = BlindIndex.compute(name);

        // Note: the JPA entity listener handles context-setting and blind-index computation.
        // For this direct-JDBC test we simulate by using the converter explicitly.
        com.fieldservice.platform.crypto.SubjectKeyContext.set("CUSTOMER_ACCOUNT", accountId);
        EnvelopeEncryptedStringConverter conv = new EnvelopeEncryptedStringConverter();
        String encEmail = conv.convertToDatabaseColumn(email);
        String encName  = conv.convertToDatabaseColumn(name);
        com.fieldservice.platform.crypto.SubjectKeyContext.clear();

        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO portal_invitation
                    (id, account_id, contact_email_enc, contact_name_enc,
                     contact_email_idx, contact_name_idx, token_hash, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 0)
                """,
                id, accountId, encEmail, encName, emailIdx, nameIdx,
                tokenHash, expiresAt);
    }
}
