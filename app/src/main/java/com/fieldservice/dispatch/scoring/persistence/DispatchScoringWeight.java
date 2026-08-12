package com.fieldservice.dispatch.scoring.persistence;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Persisted weight for one scoring factor.
 *
 * <p>factor_code is the unique identifier matching the value returned by
 * {@link com.fieldservice.dispatch.scoring.ScoringFactor#factorCode()}.
 * weight >= 0 enforced by DB check constraint; negative values are rejected
 * at load time in {@link ScoringWeightsLoader} as well.
 */
@Audited
@Entity
@Table(name = "dispatch_scoring_weight")
public class DispatchScoringWeight extends BaseEntity {

    @Column(name = "factor_code", nullable = false, unique = true, updatable = false)
    private String factorCode;

    @Column(name = "weight", nullable = false, precision = 6, scale = 4)
    private BigDecimal weight;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DispatchScoringWeight() {}

    public DispatchScoringWeight(String factorCode, BigDecimal weight, boolean active) {
        this.factorCode = factorCode;
        this.weight     = weight;
        this.active     = active;
    }

    public String getFactorCode() { return factorCode; }

    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
