package com.fieldservice.portal.domain;

import com.fieldservice.platform.crypto.BlindIndex;
import com.fieldservice.platform.crypto.EnvelopeEncryptedStringConverter;
import com.fieldservice.platform.crypto.SubjectKeyContextListener;
import com.fieldservice.platform.crypto.SubjectKeyContextProvider;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use portal invitation linking a contact address to a customer account.
 *
 * <p>The raw token is a 256-bit SecureRandom value base64url-encoded; only the
 * SHA-256 hex digest ({@code token_hash}) is persisted — the raw token is sent
 * to the invitee's email and never stored.
 *
 * <p>{@code contactEmail} and {@code contactName} are field-encrypted using
 * per-subject AES-256-GCM envelope encryption ({@link EnvelopeEncryptedStringConverter})
 * and classified Confidential per BR-23. Destroying the subject key renders all
 * ciphertext copies — live rows, audit history, replicas, backups — permanently
 * unreadable, satisfying the right-to-erasure requirement (WO-095).
 *
 * <p>Blind-index columns ({@code contact_email_idx}) support equality lookup without
 * requiring plaintext decryption of the whole table.
 *
 * <p>Both fields are now audited by Envers as ciphertext so audit history is
 * protected by the same per-subject key.
 */
@DataClassification(tier = ClassificationTier.CONFIDENTIAL,
        note = "contactEmail and contactName are PII — envelope encrypted, key destroyable")
@Audited
@Entity
@EntityListeners(SubjectKeyContextListener.class)
@Table(name = "portal_invitation")
public class PortalInvitation extends BaseEntity implements SubjectKeyContextProvider {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "contact_email_enc", nullable = false, length = 512)
    private String contactEmail;

    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "contact_name_enc", length = 512)
    private String contactName;

    /** HMAC-SHA-256 blind index for contactEmail equality lookup (range/sort unsupported). */
    @Column(name = "contact_email_idx", length = 64)
    private String contactEmailIdx;

    /** HMAC-SHA-256 blind index for contactName equality lookup (range/sort unsupported). */
    @Column(name = "contact_name_idx", length = 64)
    private String contactNameIdx;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PortalInvitation() {
    }

    public PortalInvitation(UUID accountId, String contactEmail, String contactName,
                            String tokenHash, Instant expiresAt) {
        this.accountId = accountId;
        this.contactEmail = contactEmail;
        this.contactName = contactName;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getEnvelopeSubjectType() { return "CUSTOMER_ACCOUNT"; }

    @Override
    public UUID getEnvelopeSubjectId() { return accountId; }

    @Override
    public void recomputeBlindIndices() {
        this.contactEmailIdx = BlindIndex.compute(contactEmail);
        this.contactNameIdx  = BlindIndex.compute(contactName);
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactEmailIdx() {
        return contactEmailIdx;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(Instant at) {
        this.consumedAt = at;
    }
}
