package com.fieldservice.domain.sla;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An SLA policy row defining response and resolution time targets per priority.
 *
 * <p>A row is active when {@code effective_to} is NULL. No code may hard-code SLA
 * targets: all deadline derivation must query this table for the current active policy.
 * An absent or inactive row for a priority must be detected explicitly and fail.
 *
 * <p>Not a scoped entity: SLA policies are configuration data read by any authenticated
 * principal to derive deadline information.
 */
@Audited
@Entity
@Table(name = "sla_policy")
public class SlaPolicy {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "priority", nullable = false, length = 10)
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

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SlaPolicy() {
    }

    public UUID getId() { return id; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public int getResponseMinutes() { return responseMinutes; }
    public void setResponseMinutes(int responseMinutes) { this.responseMinutes = responseMinutes; }

    public int getResolutionMinutes() { return resolutionMinutes; }
    public void setResolutionMinutes(int resolutionMinutes) { this.resolutionMinutes = resolutionMinutes; }

    public BigDecimal getAtRiskFraction() { return atRiskFraction; }
    public void setAtRiskFraction(BigDecimal atRiskFraction) { this.atRiskFraction = atRiskFraction; }

    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Instant getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Instant effectiveTo) { this.effectiveTo = effectiveTo; }

    public Instant getCreatedAt() { return createdAt; }

    public Integer getVersion() { return version; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
