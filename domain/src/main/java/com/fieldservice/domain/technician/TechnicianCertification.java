package com.fieldservice.domain.technician;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "technician_certification")
@Audited
public class TechnicianCertification implements ScopedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "technician_id", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private Technician technician;

    @Column(name = "cert_type", nullable = false, length = 100)
    private String certType;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TechnicianCertification() {}

    public TechnicianCertification(Technician technician, String certType,
                                   Instant issuedAt, Instant expiresAt) {
        this.id = UuidV7.generate();
        this.technician = technician;
        this.certType = certType;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Technician getTechnician() { return technician; }
    public String getCertType() { return certType; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
