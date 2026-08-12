package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted weekly snapshot of the certification readiness aggregate.
 *
 * <p>Keyed on {@code iso_week} (e.g. {@code "2026-W33"}) so regenerating the same
 * period performs an upsert rather than duplicating rows.
 * Historical snapshots are immutable once stored; changing the completeness
 * definition affects only future snapshots.
 */
@Entity
@Table(name = "readiness_snapshot")
class ReadinessSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "iso_week", nullable = false, unique = true)
    private String isoWeek;

    @Column(name = "readiness_percent", precision = 5, scale = 2)
    private BigDecimal readinessPercent;

    @Column(name = "complete_technicians", nullable = false)
    private int completeTechnicians;

    @Column(name = "active_technicians", nullable = false)
    private int activeTechnicians;

    @Column(name = "gate_met", nullable = false)
    private boolean gateMet;

    @Column(name = "definition_version", nullable = false)
    private String definitionVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected ReadinessSnapshotEntity() {}

    ReadinessSnapshotEntity(String isoWeek, BigDecimal readinessPercent,
                             int completeTechnicians, int activeTechnicians,
                             boolean gateMet, String definitionVersion) {
        this.id                  = UuidV7.generate();
        this.isoWeek             = isoWeek;
        this.readinessPercent    = readinessPercent;
        this.completeTechnicians = completeTechnicians;
        this.activeTechnicians   = activeTechnicians;
        this.gateMet             = gateMet;
        this.definitionVersion   = definitionVersion;
        this.generatedAt         = Instant.now();
    }

    UUID       getId()                  { return id; }
    String     getIsoWeek()             { return isoWeek; }
    BigDecimal getReadinessPercent()    { return readinessPercent; }
    int        getCompleteTechnicians() { return completeTechnicians; }
    int        getActiveTechnicians()   { return activeTechnicians; }
    boolean    isGateMet()              { return gateMet; }
    String     getDefinitionVersion()   { return definitionVersion; }
    Instant    getGeneratedAt()         { return generatedAt; }

    void update(BigDecimal readinessPercent, int completeTechnicians,
                int activeTechnicians, boolean gateMet, String definitionVersion) {
        this.readinessPercent    = readinessPercent;
        this.completeTechnicians = completeTechnicians;
        this.activeTechnicians   = activeTechnicians;
        this.gateMet             = gateMet;
        this.definitionVersion   = definitionVersion;
        this.generatedAt         = Instant.now();
    }
}
