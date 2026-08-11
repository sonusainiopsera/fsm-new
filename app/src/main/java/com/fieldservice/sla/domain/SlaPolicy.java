package com.fieldservice.sla.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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

    /** Fraction of the SLA window at which a work order is flagged at-risk (default 0.80). */
    @Column(name = "at_risk_fraction", nullable = false, precision = 3, scale = 2)
    private BigDecimal atRiskFraction = new BigDecimal("0.80");

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SlaPolicy() {}

    public SlaPolicy(String priority, int responseMinutes, int resolutionMinutes,
                     Instant effectiveFrom) {
        this.id                = UuidV7.generate();
        this.priority          = priority;
        this.responseMinutes   = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.effectiveFrom     = effectiveFrom;
    }

    public UUID       getId()                { return id; }
    public String     getPriority()          { return priority; }
    public Integer    getResponseMinutes()   { return responseMinutes; }
    public Integer    getResolutionMinutes() { return resolutionMinutes; }
    public BigDecimal getAtRiskFraction()    { return atRiskFraction; }
    public Instant    getEffectiveFrom()     { return effectiveFrom; }
    public Instant    getEffectiveTo()       { return effectiveTo; }
    public Instant    getCreatedAt()         { return createdAt; }
}
