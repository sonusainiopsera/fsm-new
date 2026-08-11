package com.fieldservice.analytics.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the {@code processed_event} table.
 *
 * <p>Enforces idempotency: one row per outbox event_id. A second delivery of the
 * same event_id is detected at insert time via the primary-key constraint.
 *
 * <p>Package-private: used only by {@link KpiOutboxConsumer}.
 */
@Entity
@Table(name = "processed_event")
class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "metric_keys", nullable = false, updatable = false)
    private String metricKeys;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    ProcessedEventEntity(UUID eventId, String metricKeys, Instant processedAt) {
        this.eventId     = eventId;
        this.metricKeys  = metricKeys;
        this.processedAt = processedAt;
    }

    UUID    getEventId()    { return eventId; }
    String  getMetricKeys() { return metricKeys; }
    Instant getProcessedAt(){ return processedAt; }
}
