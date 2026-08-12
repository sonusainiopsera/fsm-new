package com.fieldservice.dispatch.scoring.persistence;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Scalar tuning configuration entry.
 *
 * <p>Currently holds:
 * <ul>
 *   <li>{@code WORKLOAD_PENALTY_EXPONENT} — the super-linear exponent applied in
 *       {@link com.fieldservice.dispatch.scoring.factors.WorkloadFairnessFactor}.
 *       Must be &gt; 1 to maintain super-linearity; validated at load time.</li>
 * </ul>
 */
@Audited
@Entity
@Table(name = "dispatch_scoring_config")
public class DispatchScoringConfig extends BaseEntity {

    @Column(name = "config_key", nullable = false, unique = true, updatable = false)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private BigDecimal configValue;

    protected DispatchScoringConfig() {}

    public DispatchScoringConfig(String configKey, BigDecimal configValue) {
        this.configKey   = configKey;
        this.configValue = configValue;
    }

    public String getConfigKey() { return configKey; }

    public BigDecimal getConfigValue() { return configValue; }
    public void setConfigValue(BigDecimal configValue) { this.configValue = configValue; }
}
