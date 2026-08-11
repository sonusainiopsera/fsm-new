package com.fieldservice.domain.technician;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A certification held by a technician, used by the dispatch feasibility gate (BR-01).
 *
 * <p>Not a scoped entity: certifications are internal operational data queried only
 * by dispatchers and privileged roles. Read access is controlled at the service layer
 * via {@code @PreAuthorize}, not row-level scope.
 *
 * <p>Note: this table has no {@code version} or {@code updated_at} columns because
 * certifications are immutable once issued; revocation sets {@code is_revoked = true}.
 */
@Entity
@Table(name = "technician_certification")
public class TechnicianCertification {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "cert_type", nullable = false, length = 100)
    private String certType;

    @Column(name = "cert_reference", length = 100)
    private String certReference;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TechnicianCertification() {
    }

    public UUID getId() { return id; }

    public UUID getTechnicianId() { return technicianId; }
    public void setTechnicianId(UUID technicianId) { this.technicianId = technicianId; }

    public String getCertType() { return certType; }
    public void setCertType(String certType) { this.certType = certType; }

    public String getCertReference() { return certReference; }
    public void setCertReference(String certReference) { this.certReference = certReference; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public Instant getCreatedAt() { return createdAt; }
}
