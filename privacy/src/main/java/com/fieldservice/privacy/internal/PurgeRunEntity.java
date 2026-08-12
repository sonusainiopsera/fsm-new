package com.fieldservice.privacy.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit record for every purge sweep execution.
 *
 * <p>No update or delete repository methods are exposed for this entity.
 * Rows are written once and never mutated; the Envers audit table captures
 * the INSERT revision as evidence.
 */
@Audited
@Entity
@Table(name = "purge_run")
class PurgeRunEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "data_category", nullable = false, length = 100)
    private String dataCategory;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cutoff_instant")
    private Instant cutoffInstant;

    @Column(name = "rows_examined", nullable = false)
    private long rowsExamined;

    @Column(name = "rows_disposed", nullable = false)
    private long rowsDisposed;

    @Column(name = "rows_skipped", nullable = false)
    private long rowsSkipped;

    @Column(name = "skip_reasons", columnDefinition = "jsonb")
    private String skipReasons;

    @Column(name = "outcome", length = 50)
    private String outcome;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    protected PurgeRunEntity() {}

    PurgeRunEntity(String dataCategory, Instant startedAt, Instant cutoffInstant) {
        this.id            = UuidV7.generate();
        this.dataCategory  = dataCategory;
        this.startedAt     = startedAt;
        this.cutoffInstant = cutoffInstant;
        this.rowsExamined  = 0L;
        this.rowsDisposed  = 0L;
        this.rowsSkipped   = 0L;
    }

    void finish(Instant finishedAt, long examined, long disposed, long skipped,
                String skipReasonsJson, String outcome, String errorCode) {
        this.finishedAt  = finishedAt;
        this.rowsExamined = examined;
        this.rowsDisposed = disposed;
        this.rowsSkipped  = skipped;
        this.skipReasons  = skipReasonsJson;
        this.outcome      = outcome;
        this.errorCode    = errorCode;
    }

    UUID    getId()            { return id; }
    String  getDataCategory()  { return dataCategory; }
    Instant getStartedAt()     { return startedAt; }
    Instant getFinishedAt()    { return finishedAt; }
    Instant getCutoffInstant() { return cutoffInstant; }
    long    getRowsExamined()  { return rowsExamined; }
    long    getRowsDisposed()  { return rowsDisposed; }
    long    getRowsSkipped()   { return rowsSkipped; }
    String  getSkipReasons()   { return skipReasons; }
    String  getOutcome()       { return outcome; }
    String  getErrorCode()     { return errorCode; }
}
