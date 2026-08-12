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
 * JPA entity for {@code dispatch_scoring_weight}.
 * Audited by Envers so every weight change is attributable to an actor.
 */
@Audited
@Entity
@Table(name = "dispatch_scoring_weight")
public class DispatchScoringWeight {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "factor_code", nullable = false, unique = true)
    private String factorCode;

    @Column(name = "weight", nullable = false, precision = 6, scale = 4)
    private BigDecimal weight;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected DispatchScoringWeight() {}

    public UUID       getId()        { return id; }
    public String     getFactorCode(){ return factorCode; }
    public BigDecimal getWeight()    { return weight; }
    public boolean    isActive()     { return active; }
    public Integer    getVersion()   { return version; }

    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public void setActive(boolean active)    { this.active = active; }
}
