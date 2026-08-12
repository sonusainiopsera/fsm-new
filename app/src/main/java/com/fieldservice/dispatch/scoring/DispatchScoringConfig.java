package com.fieldservice.dispatch.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA entity for {@code dispatch_scoring_config}.
 * Audited by Envers so every configuration change is attributable to an actor.
 */
@Audited
@Entity
@Table(name = "dispatch_scoring_config")
public class DispatchScoringConfig {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "config_key", nullable = false, unique = true)
    private String configKey;

    @Column(name = "config_value", nullable = false, precision = 8, scale = 4)
    private BigDecimal configValue;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected DispatchScoringConfig() {}

    public UUID       getId()         { return id; }
    public String     getConfigKey()  { return configKey; }
    public BigDecimal getConfigValue(){ return configValue; }
    public Integer    getVersion()    { return version; }

    public void setConfigValue(BigDecimal value) { this.configValue = value; }
}
