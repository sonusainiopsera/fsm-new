package com.fieldservice.domain.sla;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sla_policy")
public class SlaPolicy implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String priority;

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "at_risk_fraction", nullable = false, precision = 3, scale = 2)
    private BigDecimal atRiskFraction;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SlaPolicy() {}

    public SlaPolicy(String priority, int responseMinutes, int resolutionMinutes,
                     BigDecimal atRiskFraction, Instant effectiveFrom) {
        this.id = UuidV7.generate();
        this.priority = priority;
        this.responseMinutes = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.atRiskFraction = atRiskFraction;
        this.effectiveFrom = effectiveFrom;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPriority() { return priority; }
    public int getResponseMinutes() { return responseMinutes; }
    public int getResolutionMinutes() { return resolutionMinutes; }
    public BigDecimal getAtRiskFraction() { return atRiskFraction; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
}
