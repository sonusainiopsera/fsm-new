package com.fieldservice.analytics.internal.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code repeat_visit_link} table.
 * Package-private — managed exclusively by {@link RepeatVisitLinker}.
 */
@Entity
@Table(name = "repeat_visit_link")
class RepeatVisitLinkEntity {

    @Id
    private UUID id;

    @Column(name = "earlier_work_order_id", nullable = false)
    private UUID earlierWorkOrderId;

    @Column(name = "later_work_order_id", nullable = false)
    private UUID laterWorkOrderId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "fault_key", nullable = false, length = 200)
    private String faultKey;

    @Column(name = "days_between", nullable = false)
    private int daysBetween;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected RepeatVisitLinkEntity() {}

    RepeatVisitLinkEntity(UUID id, UUID earlierWorkOrderId, UUID laterWorkOrderId,
                          UUID assetId, String faultKey, int daysBetween, Instant linkedAt) {
        this.id                   = id;
        this.earlierWorkOrderId   = earlierWorkOrderId;
        this.laterWorkOrderId     = laterWorkOrderId;
        this.assetId              = assetId;
        this.faultKey             = faultKey;
        this.daysBetween          = daysBetween;
        this.linkedAt             = linkedAt;
    }

    UUID    getId()                   { return id; }
    UUID    getEarlierWorkOrderId()   { return earlierWorkOrderId; }
    UUID    getLaterWorkOrderId()     { return laterWorkOrderId; }
    UUID    getAssetId()              { return assetId; }
    String  getFaultKey()             { return faultKey; }
    int     getDaysBetween()          { return daysBetween; }
    Instant getLinkedAt()             { return linkedAt; }
}
