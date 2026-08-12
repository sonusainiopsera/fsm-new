package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.SubjectErasureView;
import com.fieldservice.privacy.api.VerificationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only tombstone recording a completed, refused or no-op subject erasure.
 *
 * <p>This entity intentionally has no update or delete methods.  The repository
 * exposes only save (insert) and read operations.  No column holds a personal-data
 * value: {@code erased_sections} and {@code verification_result} contain structural
 * metadata (names and counts) only.
 */
@Audited
@Entity
@Table(name = "subject_erasure")
class SubjectErasureEntity {

    static final String OUTCOME_COMPLETED       = "COMPLETED";
    static final String OUTCOME_IDEMPOTENT_NOOP = "IDEMPOTENT_NOOP";
    static final String OUTCOME_REFUSED         = "REFUSED";

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "dsar_request_id", nullable = false, updatable = false)
    private UUID dsarRequestId;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 100)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "key_reference", nullable = false, updatable = false, length = 255)
    private String keyReference;

    @Column(name = "erased_at", nullable = false, updatable = false)
    private Instant erasedAt;

    @Column(name = "actor", nullable = false, updatable = false, length = 255)
    private String actor;

    @Column(name = "erased_sections", nullable = false, columnDefinition = "jsonb")
    private String erasedSections;

    @Column(name = "verification_result", nullable = false, columnDefinition = "jsonb")
    private String verificationResult;

    @Column(name = "outcome", nullable = false, updatable = false, length = 30)
    private String outcome;

    @Column(name = "refusal_reason", columnDefinition = "text")
    private String refusalReason;

    protected SubjectErasureEntity() {}

    static SubjectErasureEntity create(
            UUID id, UUID dsarRequestId, String subjectType, UUID subjectId,
            String keyReference, Instant erasedAt, String actor,
            String erasedSectionsJson, String verificationResultJson,
            String outcome, String refusalReason) {
        SubjectErasureEntity e = new SubjectErasureEntity();
        e.id                 = id;
        e.dsarRequestId      = dsarRequestId;
        e.subjectType        = subjectType;
        e.subjectId          = subjectId;
        e.keyReference       = keyReference;
        e.erasedAt           = erasedAt;
        e.actor              = actor;
        e.erasedSections     = erasedSectionsJson;
        e.verificationResult = verificationResultJson;
        e.outcome            = outcome;
        e.refusalReason      = refusalReason;
        return e;
    }

    SubjectErasureView toView(ObjectMapper mapper) {
        List<SubjectErasureView.ErasedSection> sections = parseErasedSections(mapper);
        List<VerificationResult> verResults = parseVerificationResults(mapper);
        return new SubjectErasureView(
                id, dsarRequestId, subjectType, subjectId, keyReference,
                erasedAt, actor, sections, verResults, outcome, refusalReason);
    }

    private List<SubjectErasureView.ErasedSection> parseErasedSections(ObjectMapper mapper) {
        try {
            return mapper.readValue(erasedSections,
                    new TypeReference<List<SubjectErasureView.ErasedSection>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<VerificationResult> parseVerificationResults(ObjectMapper mapper) {
        try {
            return mapper.readValue(verificationResult,
                    new TypeReference<List<VerificationResult>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    UUID    getId()          { return id; }
    UUID    getDsarRequestId(){ return dsarRequestId; }
    String  getSubjectType() { return subjectType; }
    UUID    getSubjectId()   { return subjectId; }
    String  getOutcome()     { return outcome; }
}
