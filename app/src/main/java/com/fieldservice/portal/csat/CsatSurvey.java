package com.fieldservice.portal.csat;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer satisfaction survey issued on work order closure (WO-173).
 *
 * <p>Created by {@link CsatIssuanceConsumer} in response to a {@code WorkOrderStateChanged}
 * event with {@code toState=CLOSED}. The {@code source_event_id} uniqueness constraint
 * guarantees exactly-one survey per closure under at-least-once outbox delivery.
 *
 * <p>The work-order uniqueness constraint prevents a second survey being issued if the
 * work order is reopened and closed again — the existing survey is reused per policy.
 */
@Audited
@Entity
@Table(name = "csat_survey")
public class CsatSurvey extends BaseEntity {

    @Column(name = "work_order_id", nullable = false, unique = true, updatable = false)
    private UUID workOrderId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /** Event id from the outbox event that triggered issuance; unique constraint is the idempotency guard. */
    @Column(name = "source_event_id", nullable = false, unique = true, updatable = false)
    private UUID sourceEventId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CsatSurveyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 10)
    private CsatDeliveryStatus deliveryStatus;

    protected CsatSurvey() {
    }

    public CsatSurvey(UUID workOrderId, UUID accountId, UUID sourceEventId,
                      Instant issuedAt, Instant expiresAt) {
        this.workOrderId     = workOrderId;
        this.accountId       = accountId;
        this.sourceEventId   = sourceEventId;
        this.issuedAt        = issuedAt;
        this.expiresAt       = expiresAt;
        this.status          = CsatSurveyStatus.PENDING;
        this.deliveryStatus  = CsatDeliveryStatus.PENDING;
    }

    public UUID getWorkOrderId()        { return workOrderId; }
    public UUID getAccountId()          { return accountId; }
    public UUID getSourceEventId()      { return sourceEventId; }
    public Instant getIssuedAt()        { return issuedAt; }
    public Instant getExpiresAt()       { return expiresAt; }
    public CsatSurveyStatus getStatus() { return status; }
    public CsatDeliveryStatus getDeliveryStatus() { return deliveryStatus; }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isAnswered() {
        return status == CsatSurveyStatus.ANSWERED;
    }

    public void markAnswered() {
        this.status = CsatSurveyStatus.ANSWERED;
    }

    public void markDeliveryInApp() {
        this.deliveryStatus = CsatDeliveryStatus.IN_APP;
    }

    public void markDeliverySent() {
        this.deliveryStatus = CsatDeliveryStatus.SENT;
    }

    public void markDeliveryFailed() {
        this.deliveryStatus = CsatDeliveryStatus.FAILED;
    }
}
