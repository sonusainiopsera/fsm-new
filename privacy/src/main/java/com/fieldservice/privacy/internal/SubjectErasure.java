package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.ErasureView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only tombstone row for a completed, refused, or idempotent cryptographic erasure.
 *
 * <p>This entity is Envers-audited. No UPDATE or DELETE is ever issued against the live row
 * or its audit history; it is written once and never modified.
 *
 * <p><strong>Privacy guarantee:</strong> no column holds a personal-data value. Columns are
 * limited to: opaque UUIDs, timestamps, actor principal name (not a PII value), jsonb
 * structures containing only counts and scope names, and varchar outcome/reason codes.
 */
@Audited
@Entity
@Table(name = "subject_erasure")
class SubjectErasure extends BaseEntity {

    @Column(name = "dsar_request_id", nullable = false, updatable = false)
    private UUID dsarRequestId;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 50)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "key_reference", nullable = false, updatable = false, length = 255)
    private String keyReference;

    @Column(name = "erased_at", nullable = false, updatable = false)
    private Instant erasedAt;

    @Column(name = "actor", nullable = false, updatable = false, length = 255)
    private String actor;

    @Column(name = "erased_sections", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String erasedSections;

    @Column(name = "verification_result", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String verificationResult;

    @Column(name = "outcome", nullable = false, updatable = false, length = 30)
    private String outcome;

    @Nullable
    @Column(name = "refusal_reason", updatable = false, length = 500)
    private String refusalReason;

    protected SubjectErasure() {
    }

    SubjectErasure(UUID dsarRequestId, String subjectType, UUID subjectId,
                   String keyReference, Instant erasedAt, String actor,
                   String erasedSections, String verificationResult,
                   String outcome, @Nullable String refusalReason) {
        this.dsarRequestId     = dsarRequestId;
        this.subjectType       = subjectType;
        this.subjectId         = subjectId;
        this.keyReference      = keyReference;
        this.erasedAt          = erasedAt;
        this.actor             = actor;
        this.erasedSections    = erasedSections;
        this.verificationResult = verificationResult;
        this.outcome           = outcome;
        this.refusalReason     = refusalReason;
    }

    ErasureView toView(List<ErasureView.ErasedSection> sections,
                       List<ErasureView.ScopeVerification> verifications) {
        return new ErasureView(
                getId(), subjectType, subjectId,
                erasedAt, actor,
                sections, verifications,
                outcome, refusalReason);
    }

    // ── Package-private accessors ───────────────────────────────────────────

    UUID getDsarRequestId()     { return dsarRequestId; }
    String getSubjectType()     { return subjectType; }
    UUID getSubjectId()         { return subjectId; }
    String getKeyReference()    { return keyReference; }
    Instant getErasedAt()       { return erasedAt; }
    String getActor()           { return actor; }
    String getErasedSections()  { return erasedSections; }
    String getVerificationResult() { return verificationResult; }
    String getOutcome()         { return outcome; }
    @Nullable String getRefusalReason() { return refusalReason; }
}
