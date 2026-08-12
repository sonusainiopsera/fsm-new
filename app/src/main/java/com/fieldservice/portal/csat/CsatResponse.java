package com.fieldservice.portal.csat;

import com.fieldservice.platform.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer response to a {@link CsatSurvey}.
 *
 * <p>One response per survey — enforced by {@code UNIQUE(survey_id)}.
 *
 * <h3>PII handling</h3>
 * The {@code comment} field is customer free text classified as
 * <em>Confidential</em> (BR-23). It is field-encrypted at rest using
 * {@link EncryptedStringConverter} (AES-256-GCM) and must never appear in
 * any log output at any level. Only the survey id and account id are logged.
 *
 * <p>Retention category: {@code CSAT_RESPONSE} — 12 months after relationship end (BR-25).
 */
@Entity
@Audited
@Table(name = "csat_response")
public class CsatResponse {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "survey_id", updatable = false, nullable = false)
    private UUID surveyId;

    @Column(name = "score", nullable = false)
    private short score;

    @Column(name = "nps_score")
    private Short npsScore;

    /** Encrypted at rest — must not appear in logs. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "comment", length = 2048)
    private String comment;

    @Column(name = "submitted_at", updatable = false, nullable = false)
    private Instant submittedAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected CsatResponse() {}

    public static CsatResponse submit(UUID id, UUID surveyId,
                                      short score, Short npsScore,
                                      String comment, Instant submittedAt) {
        CsatResponse r = new CsatResponse();
        r.id          = id;
        r.surveyId    = surveyId;
        r.score       = score;
        r.npsScore    = npsScore;
        r.comment     = comment;
        r.submittedAt = submittedAt;
        r.version     = 0;
        return r;
    }

    public UUID getId()           { return id; }
    public UUID getSurveyId()     { return surveyId; }
    public short getScore()       { return score; }
    public Short getNpsScore()    { return npsScore; }
    public String getComment()    { return comment; }
    public Instant getSubmittedAt() { return submittedAt; }
    public int getVersion()       { return version; }
}
