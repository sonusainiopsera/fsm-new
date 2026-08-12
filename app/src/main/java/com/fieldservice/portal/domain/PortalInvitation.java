package com.fieldservice.portal.domain;

import com.fieldservice.platform.crypto.EnvelopeEncryptedStringConverter;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use invitation for portal onboarding.
 *
 * <p>Maps to {@code portal_invitation}.
 *
 * <h3>Token security</h3>
 * The plaintext token is a 256-bit SecureRandom value returned once at issuance and
 * never stored. Only the SHA-256 hex digest ({@code token_hash}) is persisted.
 *
 * <h3>Field encryption (WO-193)</h3>
 * {@code contactEmail} and {@code contactName} are classified Confidential (BR-23) and
 * are stored as per-subject AES-256-GCM envelope ciphertext via
 * {@link EnvelopeEncryptedStringConverter}. Decrypted values are never written to logs.
 *
 * <h3>Blind index</h3>
 * {@code contactEmailIdx} is an HMAC-SHA-256 blind index over the normalised plaintext
 * email, keyed separately from the data-encryption key.  It supports equality lookup
 * for portal identity resolution without decrypting the full table.  Range/prefix
 * queries on contact email are not supported and must be redesigned.
 * Callers must populate {@code contactEmailIdx} via
 * {@link com.fieldservice.platform.crypto.BlindIndex#compute} before persisting.
 */
@Audited
@Entity
@Table(name = "portal_invitation")
public class PortalInvitation {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /** Envelope-encrypted AES-256-GCM; stored as ENCv1 envelope string. */
    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "contact_email", length = 2048)
    private String contactEmail;

    /** Envelope-encrypted AES-256-GCM; stored as ENCv1 envelope string. */
    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "contact_name", length = 1024)
    private String contactName;

    /**
     * HMAC-SHA-256 blind index of normalised {@code contactEmail} for equality lookup.
     * Null until explicitly set by the service layer.  Equality only — no range search.
     */
    @Column(name = "contact_email_idx", length = 64)
    private String contactEmailIdx;

    /** SHA-256 hex digest of the opaque token; the plaintext token is never stored. */
    @NotAudited
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected PortalInvitation() {}

    public PortalInvitation(UUID accountId, String contactEmail, String contactName,
                             String tokenHash, Instant expiresAt, UUID createdBy) {
        this.id           = UuidV7.generate();
        this.accountId    = accountId;
        this.contactEmail = contactEmail;
        this.contactName  = contactName;
        this.tokenHash    = tokenHash;
        this.expiresAt    = expiresAt;
        this.createdBy    = createdBy;
    }

    /**
     * Marks the invitation as consumed. Single-use enforcement: throws
     * {@link IllegalStateException} if already consumed or expired.
     */
    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException("Invitation already consumed: " + id);
        }
        if (now.isAfter(expiresAt)) {
            throw new IllegalStateException("Invitation expired: " + id);
        }
        this.consumedAt = now;
    }

    public boolean isActive(Instant now) {
        return consumedAt == null && !now.isAfter(expiresAt);
    }

    public UUID    getId()               { return id; }
    public UUID    getAccountId()        { return accountId; }
    public String  getContactEmail()     { return contactEmail; }
    public String  getContactName()      { return contactName; }
    public String  getContactEmailIdx()  { return contactEmailIdx; }
    public String  getTokenHash()        { return tokenHash; }
    public Instant getExpiresAt()        { return expiresAt; }
    public Instant getConsumedAt()       { return consumedAt; }
    public UUID    getCreatedBy()        { return createdBy; }
    public Instant getCreatedAt()        { return createdAt; }
    public Integer getVersion()          { return version; }

    public void setContactEmailIdx(String contactEmailIdx) {
        this.contactEmailIdx = contactEmailIdx;
    }
}
