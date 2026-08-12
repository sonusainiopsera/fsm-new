package com.fieldservice.portal.csat;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * CSAT survey issued on work order closure.
 *
 * <p>Exactly one survey per work order — enforced by {@code UNIQUE(work_order_id)} and
 * {@code UNIQUE(source_event_id)} at the database level. The outbox consumer guarantees
 * idempotent issuance: replaying the closure event creates at most one survey row.
 *
 * <p>Retention category: {@code CSAT_DATA} — 12 months after relationship end (BR-25).
 */
@Entity
@Audited
@Table(name = "csat_survey")
public class CsatSurvey {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "work_order_id", updatable = false, nullable = false)
    private UUID workOrderId;

    @Column(name = "account_id", updatable = false, nullable = false)
    private UUID accountId;

    @Column(name = "source_event_id", updatable = false, nullable = false)
    private UUID sourceEventId;

    @Column(name = "issued_at", updatable = false, nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CsatSurveyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    private CsatDeliveryStatus deliveryStatus;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected CsatSurvey() {}

    public static CsatSurvey issue(UUID id, UUID workOrderId, UUID accountId,
                                   UUID sourceEventId, Instant issuedAt, Instant expiresAt) {
        CsatSurvey s = new CsatSurvey();
        s.id              = id;
        s.workOrderId     = workOrderId;
        s.accountId       = accountId;
        s.sourceEventId   = sourceEventId;
        s.issuedAt        = issuedAt;
        s.expiresAt       = expiresAt;
        s.status          = CsatSurveyStatus.PENDING;
        s.deliveryStatus  = CsatDeliveryStatus.PENDING;
        s.version         = 0;
        return s;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public void markAnswered() {
        this.status = CsatSurveyStatus.ANSWERED;
    }

    public void markExpired() {
        this.status = CsatSurveyStatus.EXPIRED;
    }

    public void setDeliveryStatus(CsatDeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public UUID getId()                    { return id; }
    public UUID getWorkOrderId()           { return workOrderId; }
    public UUID getAccountId()             { return accountId; }
    public UUID getSourceEventId()         { return sourceEventId; }
    public Instant getIssuedAt()           { return issuedAt; }
    public Instant getExpiresAt()          { return expiresAt; }
    public CsatSurveyStatus getStatus()    { return status; }
    public CsatDeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public int getVersion()                { return version; }
}
