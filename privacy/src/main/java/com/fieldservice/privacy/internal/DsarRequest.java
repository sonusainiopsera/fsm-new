package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.DsarRequestView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for {@code dsar_request} rows.
 *
 * <p>Package-private — all external access must go through
 * {@link com.fieldservice.privacy.api.DsarAdminPort}.
 *
 * <p>Audited with Hibernate Envers; every state transition produces a revision.
 */
@Audited
@Entity
@Table(name = "dsar_request")
class DsarRequest extends BaseEntity {

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(name = "subject_type", nullable = false, length = 50)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    @Nullable
    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Nullable
    @Column(name = "verification_method", length = 30)
    private String verificationMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private DsarState state = DsarState.RECEIVED;

    @Nullable
    @Column(name = "assigned_handler")
    private UUID assignedHandler;

    @Nullable
    @Column(name = "outcome", length = 30)
    private String outcome;

    @Nullable
    @Column(name = "outcome_note", columnDefinition = "text")
    private String outcomeNote;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Nullable
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected DsarRequest() {
    }

    DsarRequest(String requestType, String subjectType, UUID subjectId,
                Instant submittedAt, Instant dueAt, @Nullable String notes) {
        this.requestType = requestType;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.submittedAt = submittedAt;
        this.dueAt = dueAt;
        this.notes = notes;
        this.state = DsarState.RECEIVED;
    }

    void applyTransition(DsarState newState, @Nullable String verificationMethod,
                         @Nullable String note, @Nullable String outcome) {
        this.state = newState;
        if (verificationMethod != null) {
            this.verificationMethod = verificationMethod;
            this.identityVerifiedAt = Instant.now();
        }
        if (note != null && this.outcomeNote == null) {
            this.outcomeNote = note;
        }
        if (outcome != null) {
            this.outcome = outcome;
        }
        if (newState == DsarState.IN_PROGRESS) {
            this.attemptCount++;
        }
    }

    DsarRequestView toView(long remainingDays, boolean atRisk) {
        return new DsarRequestView(
                getId(), requestType, subjectType, subjectId,
                submittedAt, dueAt, identityVerifiedAt, verificationMethod,
                state.name(), assignedHandler, outcome, outcomeNote,
                attemptCount, notes, remainingDays, atRisk,
                getVersion() == null ? 0 : getVersion(),
                getCreatedAt(), getUpdatedAt());
    }

    // ── Package-private accessors ───────────────────────────────────────────────

    String getRequestType() { return requestType; }
    String getSubjectType() { return subjectType; }
    UUID getSubjectId() { return subjectId; }
    Instant getSubmittedAt() { return submittedAt; }
    Instant getDueAt() { return dueAt; }
    @Nullable Instant getIdentityVerifiedAt() { return identityVerifiedAt; }
    @Nullable String getVerificationMethod() { return verificationMethod; }
    DsarState getState() { return state; }
    @Nullable UUID getAssignedHandler() { return assignedHandler; }
    @Nullable String getOutcome() { return outcome; }
    @Nullable String getOutcomeNote() { return outcomeNote; }
    int getAttemptCount() { return attemptCount; }
    @Nullable String getNotes() { return notes; }
}
