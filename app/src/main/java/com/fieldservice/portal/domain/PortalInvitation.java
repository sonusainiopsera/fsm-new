package com.fieldservice.portal.domain;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.EncryptedStringConverter;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

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
 * AES-256-GCM ({@link EncryptedStringConverter}) and classified Confidential per
 * BR-23. They must never appear in logs, event payloads, or audit tables.
 *
 * <p>The Envers audit mirrors {@code contact_email_enc} and {@code contact_name_enc}
 * intentionally — the audit captures the fact of invitation issuance (accountId,
 * token_hash, expiresAt) without storing the cleartext contact details.
 */
@DataClassification(tier = ClassificationTier.CONFIDENTIAL,
        note = "contactEmail and contactName are PII — encrypted at rest, never logged")
@Audited
@Entity
@Table(name = "portal_invitation")
public class PortalInvitation extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    // contact_email_enc in DB — AES-256-GCM ciphertext; never logged
    @NotAudited
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_email_enc", nullable = false, length = 512)
    private String contactEmail;

    // contact_name_enc in DB — AES-256-GCM ciphertext; never logged
    @NotAudited
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_name_enc", length = 512)
    private String contactName;

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

    public UUID getAccountId() {
        return accountId;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactName() {
        return contactName;
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
