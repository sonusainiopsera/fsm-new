package com.fieldservice.sla.domain;

import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@DataClassification(value = ClassificationTier.PUBLIC, module = "sla")
@Audited
@Entity
@Table(name = "sla_policy")
public class SlaPolicy {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String priority;

    @Column(name = "response_minutes", nullable = false)
    private Integer responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private Integer resolutionMinutes;

    @Column(name = "at_risk_fraction", nullable = false, precision = 3, scale = 2)
    private BigDecimal atRiskFraction = new BigDecimal("0.80");

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private Boolean ratified = false;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected SlaPolicy() {}

    public SlaPolicy(String priority, int responseMinutes, int resolutionMinutes,
                     BigDecimal atRiskFraction, Instant effectiveFrom) {
        this.id                = UuidV7.generate();
        this.priority          = priority;
        this.responseMinutes   = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.atRiskFraction    = atRiskFraction != null ? atRiskFraction : new BigDecimal("0.80");
        this.effectiveFrom     = effectiveFrom;
    }

    public SlaPolicy(String priority, int responseMinutes, int resolutionMinutes,
                     Instant effectiveFrom) {
        this(priority, responseMinutes, resolutionMinutes, new BigDecimal("0.80"), effectiveFrom);
    }

    /** Supersede: close the effective window on this row when a newer version is created. */
    public void closeAt(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
        this.active      = false;
    }

    /**
     * In-place admin update: updates mutable fields and records who made the change.
     * JPA's {@code @Version} handles optimistic locking automatically.
     */
    public void update(int responseMinutes, int resolutionMinutes,
                       BigDecimal atRiskFraction, boolean ratified, String updatedBy) {
        this.responseMinutes   = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.atRiskFraction    = atRiskFraction;
        this.ratified          = ratified;
        this.updatedBy         = updatedBy;
        this.updatedAt         = Instant.now();
    }

    public UUID       getId()                { return id; }
    public String     getPriority()          { return priority; }
    public Integer    getResponseMinutes()   { return responseMinutes; }
    public Integer    getResolutionMinutes() { return resolutionMinutes; }
    public BigDecimal getAtRiskFraction()    { return atRiskFraction; }
    public Instant    getEffectiveFrom()     { return effectiveFrom; }
    public Instant    getEffectiveTo()       { return effectiveTo; }
    public boolean    isActive()             { return Boolean.TRUE.equals(active); }
    public boolean    isRatified()           { return Boolean.TRUE.equals(ratified); }
    public Instant    getUpdatedAt()         { return updatedAt; }
    public String     getUpdatedBy()         { return updatedBy; }
    public Instant    getCreatedAt()         { return createdAt; }
    public Integer    getVersion()           { return version; }
}
