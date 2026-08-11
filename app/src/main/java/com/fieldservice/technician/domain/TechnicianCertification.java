package com.fieldservice.technician.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "technician_certification")
public class TechnicianCertification {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "certification_code", nullable = false, length = 50)
    private String certificationCode;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TechnicianCertification() {}

    public TechnicianCertification(UUID technicianId, String certificationCode,
                                    Instant issuedAt, Instant expiresAt) {
        this.id                = UuidV7.generate();
        this.technicianId      = technicianId;
        this.certificationCode = certificationCode;
        this.issuedAt          = issuedAt;
        this.expiresAt         = expiresAt;
    }

    public UUID    getId()                { return id; }
    public UUID    getTechnicianId()      { return technicianId; }
    public String  getCertificationCode() { return certificationCode; }
    public Instant getIssuedAt()          { return issuedAt; }
    public Instant getExpiresAt()         { return expiresAt; }
    public Instant getCreatedAt()         { return createdAt; }
}
