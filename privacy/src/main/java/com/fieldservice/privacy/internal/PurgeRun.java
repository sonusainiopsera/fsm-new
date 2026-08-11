package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Append-only audit record for a single purge sweep execution.
 *
 * <p>Package-private — external callers must not update or delete these rows.
 * The entity exposes no public setters; all fields are initialised at construction time
 * and the {@link PurgeRunRepository} deliberately exposes no update or delete methods.
 *
 * <p>Audited with Hibernate Envers so the revision history reflects every sweep run
 * with {@code actor = system-purge-sweep}.
 */
@Audited
@Entity
@Table(name = "purge_run")
class PurgeRun extends BaseEntity {

    @Column(name = "data_category", nullable = false, length = 100)
    private String dataCategory;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Nullable
    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cutoff_instant", nullable = false)
    private Instant cutoffInstant;

    @Column(name = "rows_examined", nullable = false)
    private long rowsExamined;

    @Column(name = "rows_disposed", nullable = false)
    private long rowsDisposed;

    @Column(name = "rows_skipped", nullable = false)
    private long rowsSkipped;

    @Nullable
    @Column(name = "skip_reasons", columnDefinition = "jsonb")
    private String skipReasons;

    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;

    @Nullable
    @Column(name = "error_code", length = 100)
    private String errorCode;

    protected PurgeRun() {
    }

    PurgeRun(String dataCategory, Instant startedAt, @Nullable Instant finishedAt,
             Instant cutoffInstant, long rowsExamined, long rowsDisposed, long rowsSkipped,
             @Nullable String skipReasons, String outcome, @Nullable String errorCode) {
        this.dataCategory = dataCategory;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.cutoffInstant = cutoffInstant;
        this.rowsExamined = rowsExamined;
        this.rowsDisposed = rowsDisposed;
        this.rowsSkipped = rowsSkipped;
        this.skipReasons = skipReasons;
        this.outcome = outcome;
        this.errorCode = errorCode;
    }

    String getDataCategory() { return dataCategory; }
    Instant getStartedAt() { return startedAt; }
    @Nullable Instant getFinishedAt() { return finishedAt; }
    Instant getCutoffInstant() { return cutoffInstant; }
    long getRowsExamined() { return rowsExamined; }
    long getRowsDisposed() { return rowsDisposed; }
    long getRowsSkipped() { return rowsSkipped; }
    @Nullable String getSkipReasons() { return skipReasons; }
    String getOutcome() { return outcome; }
    @Nullable String getErrorCode() { return errorCode; }
}
