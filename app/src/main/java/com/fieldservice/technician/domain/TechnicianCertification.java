package com.fieldservice.technician.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-technician certification record.
 *
 * <p>Currency MUST be computed at query time using {@code expires_on >= :atDate}.
 * There is NO stored currency flag — a build-gate test asserts this invariant.
 *
 * <h3>Schema migration note</h3>
 * V1 introduced placeholder columns ({@code certification_code}, {@code issued_at},
 * {@code expires_at}).  V39 added the new-schema columns ({@code certification_type_id},
 * {@code issued_on}, {@code expires_on}, {@code active}).  Both column sets are retained
 * for backward compatibility with the existing {@code CertificationCurrencyGuard}.
 */
@Audited
@Entity
@Table(name = "technician_certification")
public class TechnicianCertification {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    // ---- Legacy V1 columns (used by CertificationCurrencyGuard) ---------------

    @Column(name = "certification_code", length = 50)
    private String certificationCode;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // ---- V39 new-schema columns (used by CertificationCurrencyService) --------

    @Column(name = "certification_type_id")
    private UUID certificationTypeId;

    @Column(name = "certificate_reference", length = 100)
    private String certificateReference;

    /** Canonical issue date (date only — no time zone ambiguity). */
    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /**
     * Canonical expiry date.  NULL means perpetual competency.
     *
     * <p>Currency predicate: {@code expires_on IS NULL OR expires_on >= :atDate}.
     * Do NOT cache this as a boolean column.
     */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "issuing_body", length = 255)
    private String issuingBody;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected TechnicianCertification() {}

    /** Legacy constructor — used by existing tests and CertificationCurrencyGuard. */
    public TechnicianCertification(UUID technicianId, String certificationCode,
                                    Instant issuedAt, Instant expiresAt) {
        this.id                = UuidV7.generate();
        this.technicianId      = technicianId;
        this.certificationCode = certificationCode;
        this.issuedAt          = issuedAt;
        this.expiresAt         = expiresAt;
    }

    /** New-schema constructor — used by CertificationCurrencyService. */
    public TechnicianCertification(UUID technicianId, UUID certificationTypeId,
                                    String certificateReference,
                                    LocalDate issuedOn, LocalDate expiresOn,
                                    String issuingBody) {
        this.id                   = UuidV7.generate();
        this.technicianId         = technicianId;
        this.certificationTypeId  = certificationTypeId;
        this.certificateReference = certificateReference;
        this.issuedOn             = issuedOn;
        this.expiresOn            = expiresOn;
        this.issuingBody          = issuingBody;
        this.active               = true;
    }

    // ---- Getters (legacy) ---------------------------------------------------

    public UUID    getId()                { return id; }
    public UUID    getTechnicianId()      { return technicianId; }
    public String  getCertificationCode() { return certificationCode; }
    public Instant getIssuedAt()          { return issuedAt; }
    public Instant getExpiresAt()         { return expiresAt; }
    public Instant getCreatedAt()         { return createdAt; }

    // ---- Getters (V39 new-schema) -------------------------------------------

    public UUID      getCertificationTypeId()  { return certificationTypeId; }
    public String    getCertificateReference() { return certificateReference; }
    public LocalDate getIssuedOn()             { return issuedOn; }
    public LocalDate getExpiresOn()            { return expiresOn; }
    public String    getIssuingBody()          { return issuingBody; }
    public boolean   isActive()               { return active; }
    public Integer   getVersion()             { return version; }

    public void deactivate() { this.active = false; }
}
