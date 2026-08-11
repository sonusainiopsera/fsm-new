package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.UUID;

/** Per-technician certification record. Package-private. Currency is never stored. */
@Audited
@Entity
@Table(name = "technician_certification")
class TechnicianCertificationEntity extends BaseEntity {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certification_type_id", nullable = false)
    private CertificationTypeEntity certificationType;

    @Column(name = "certificate_reference", length = 100)
    private String certificateReference;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "issuing_body", length = 255)
    private String issuingBody;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected TechnicianCertificationEntity() {}

    TechnicianCertificationEntity(UUID technicianId,
                                   CertificationTypeEntity certificationType,
                                   String certificateReference,
                                   LocalDate issuedOn,
                                   LocalDate expiresOn,
                                   String issuingBody) {
        this.technicianId         = technicianId;
        this.certificationType    = certificationType;
        this.certificateReference = certificateReference;
        this.issuedOn             = issuedOn;
        this.expiresOn            = expiresOn;
        this.issuingBody          = issuingBody;
    }

    UUID                    getTechnicianId()                       { return technicianId; }
    CertificationTypeEntity getCertificationType()                  { return certificationType; }
    void                    setCertificationType(CertificationTypeEntity t) { this.certificationType = t; }
    String                  getCertificateReference()               { return certificateReference; }
    void                    setCertificateReference(String r)       { this.certificateReference = r; }
    LocalDate               getIssuedOn()                           { return issuedOn; }
    void                    setIssuedOn(LocalDate d)                { this.issuedOn = d; }
    LocalDate               getExpiresOn()                          { return expiresOn; }
    void                    setExpiresOn(LocalDate d)               { this.expiresOn = d; }
    String                  getIssuingBody()                        { return issuingBody; }
    void                    setIssuingBody(String b)                { this.issuingBody = b; }
    boolean                 isActive()                              { return active; }
    void                    setActive(boolean a)                    { this.active = a; }
}
