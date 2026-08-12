package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "dsar_request")
class DsarRequestEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private DsarRequestType requestType;

    @Column(name = "subject_type", nullable = false, length = 100)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Column(name = "verification_method", length = 50)
    private String verificationMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private DsarState state;

    @Column(name = "assigned_handler")
    private UUID assignedHandler;

    @Column(name = "outcome", length = 50)
    private String outcome;

    @Column(name = "outcome_note", columnDefinition = "text")
    private String outcomeNote;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected DsarRequestEntity() {}

    static DsarRequestEntity create(UUID id, DsarRequestType requestType,
                                    String subjectType, UUID subjectId,
                                    Instant submittedAt, Instant dueAt, String actor) {
        DsarRequestEntity e = new DsarRequestEntity();
        e.id          = id;
        e.requestType = requestType;
        e.subjectType = subjectType;
        e.subjectId   = subjectId;
        e.submittedAt = submittedAt;
        e.dueAt       = dueAt;
        e.state       = DsarState.RECEIVED;
        e.attemptCount = 0;
        e.createdAt   = submittedAt;
        e.createdBy   = actor;
        e.updatedAt   = submittedAt;
        e.updatedBy   = actor;
        return e;
    }

    void applyTransition(DsarState newState, DsarEvent event,
                         String verificationMethod, String note,
                         Instant now, String actor) {
        if (event == DsarEvent.RECORD_VERIFICATION || event == DsarEvent.VERIFY_DIRECT) {
            this.identityVerifiedAt  = now;
            this.verificationMethod  = verificationMethod;
        }
        if (event == DsarEvent.FULFIL) {
            this.outcome = "FULFILLED";
        } else if (newState == DsarState.REJECTED) {
            this.outcome     = "REJECTED";
            this.outcomeNote = note;
        } else if (newState == DsarState.WITHDRAWN) {
            this.outcome     = "WITHDRAWN";
            this.outcomeNote = note;
        }
        if (event == DsarEvent.CLAIM) {
            this.attemptCount++;
        }
        this.state     = newState;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    DsarRequestView toView(long remainingDays, boolean atRisk) {
        return new DsarRequestView(id, requestType, subjectType, subjectId,
                state, submittedAt, dueAt, identityVerifiedAt, verificationMethod,
                assignedHandler, outcome, outcomeNote, attemptCount,
                remainingDays, atRisk, createdAt, createdBy, updatedAt, updatedBy, version);
    }

    UUID            getId()                   { return id; }
    DsarRequestType getRequestType()          { return requestType; }
    String          getSubjectType()          { return subjectType; }
    UUID            getSubjectId()            { return subjectId; }
    DsarState       getState()                { return state; }
    Instant         getSubmittedAt()          { return submittedAt; }
    Instant         getDueAt()                { return dueAt; }
    Instant         getIdentityVerifiedAt()   { return identityVerifiedAt; }
    String          getVerificationMethod()   { return verificationMethod; }
    int             getAttemptCount()         { return attemptCount; }
    int             getVersion()              { return version; }
    Instant         getUpdatedAt()            { return updatedAt; }
}
