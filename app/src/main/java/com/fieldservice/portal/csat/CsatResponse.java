package com.fieldservice.portal.csat;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.EncryptedStringConverter;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer's single response to a CSAT survey (WO-173).
 *
 * <p>{@code comment} is customer PII: Confidential classification, field-encrypted
 * at rest via {@link EncryptedStringConverter} (AES-256-GCM), never included in any
 * log line at any level. Audit revisions store the ciphertext, not plaintext.
 *
 * <p>The unique constraint on {@code survey_id} enforces one response per survey
 * at the database level (backed by AC-4).
 */
@DataClassification(tier = ClassificationTier.CONFIDENTIAL,
        note = "comment is customer PII — field-encrypted, excluded from logs")
@Audited
@Entity
@Table(name = "csat_response")
public class CsatResponse extends BaseEntity {

    @Column(name = "survey_id", nullable = false, unique = true, updatable = false)
    private UUID surveyId;

    @Column(name = "score", nullable = false)
    private short score;

    @Column(name = "nps_score")
    private Short npsScore;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Free-text customer comment — encrypted at rest, excluded from logs")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "comment_enc", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected CsatResponse() {
    }

    public CsatResponse(UUID surveyId, short score, Short npsScore, String comment,
                        Instant submittedAt) {
        this.surveyId    = surveyId;
        this.score       = score;
        this.npsScore    = npsScore;
        this.comment     = comment;
        this.submittedAt = submittedAt;
    }

    public UUID getSurveyId()    { return surveyId; }
    public short getScore()      { return score; }
    public Short getNpsScore()   { return npsScore; }
    public Instant getSubmittedAt() { return submittedAt; }

    /** Returns the decrypted comment, or null if no comment was provided. */
    public String getComment()   { return comment; }
}
