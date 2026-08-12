package com.fieldservice.domain.technician;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model for a technician's held certification.
 *
 * <p>This is a plain domain object used by fixture builders and seed generators.
 * Persistence is managed separately through the workforce module's JPA entity.
 */
public class TechnicianCertification {

    private UUID technicianId;
    private String certType;
    private String certReference;
    private Instant issuedAt;
    private Instant expiresAt;
    private boolean revoked;

    public UUID getTechnicianId()            { return technicianId; }
    public void setTechnicianId(UUID id)     { this.technicianId = id; }

    public String getCertType()              { return certType; }
    public void setCertType(String certType) { this.certType = certType; }

    public String getCertReference()         { return certReference; }
    public void setCertReference(String ref) { this.certReference = ref; }

    public Instant getIssuedAt()             { return issuedAt; }
    public void setIssuedAt(Instant issuedAt){ this.issuedAt = issuedAt; }

    public Instant getExpiresAt()            { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRevoked()               { return revoked; }
    public void setRevoked(boolean revoked)  { this.revoked = revoked; }
}
