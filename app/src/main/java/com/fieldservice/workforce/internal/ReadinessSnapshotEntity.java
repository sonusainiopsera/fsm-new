package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable weekly aggregate snapshot of the certification data-readiness gate.
 *
 * <p>Snapshots are append-only and upserted by iso_week — regenerating for the same
 * week overwrites rather than duplicating (AC-6 idempotency).
 *
 * <p>Not Envers-audited — the unique iso_week constraint combined with generated_at
 * provides provenance. Existing snapshots are never modified retroactively.
 */
@Entity
@Table(name = "readiness_snapshot")
class ReadinessSnapshotEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "iso_week", nullable = false, length = 8)
    private String isoWeek;

    /** NULL when applicable=false (zero active technicians). */
    @Column(name = "readiness_percent", precision = 5, scale = 2)
    private BigDecimal readinessPercent;

    @Column(name = "complete_technicians", nullable = false)
    private int completeTechnicians;

    @Column(name = "active_technicians", nullable = false)
    private int activeTechnicians;

    @Column(name = "gate_met", nullable = false)
    private boolean gateMet;

    @Column(name = "definition_version", nullable = false, length = 36)
    private String definitionVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "applicable", nullable = false)
    private boolean applicable = true;

    protected ReadinessSnapshotEntity() {}

    ReadinessSnapshotEntity(String isoWeek, BigDecimal readinessPercent,
                             int completeTechnicians, int activeTechnicians,
                             boolean gateMet, String definitionVersion,
                             Instant generatedAt, boolean applicable) {
        this.isoWeek               = isoWeek;
        this.readinessPercent      = readinessPercent;
        this.completeTechnicians   = completeTechnicians;
        this.activeTechnicians     = activeTechnicians;
        this.gateMet               = gateMet;
        this.definitionVersion     = definitionVersion;
        this.generatedAt           = generatedAt;
        this.applicable            = applicable;
    }

    UUID       getId()                  { return id; }
    String     getIsoWeek()             { return isoWeek; }
    BigDecimal getReadinessPercent()    { return readinessPercent; }
    int        getCompleteTechnicians() { return completeTechnicians; }
    int        getActiveTechnicians()   { return activeTechnicians; }
    boolean    isGateMet()              { return gateMet; }
    String     getDefinitionVersion()   { return definitionVersion; }
    Instant    getGeneratedAt()         { return generatedAt; }
    boolean    isApplicable()           { return applicable; }
}
