package com.fieldservice.photoanalysis.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of the technician's final description choice and the AI override
 * classification (WO-181 AC-6).
 *
 * <p>One row per (work_order_photo_id, ai_interaction_id) pair. The interaction may be
 * null when the description is recorded without a preceding analysis call (e.g. feature
 * flag off).
 */
@Entity
@Table(name = "photo_description_override")
class PhotoDescriptionOverride {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "work_order_photo_id", updatable = false, nullable = false)
    private UUID workOrderPhotoId;

    @Column(name = "ai_interaction_id", updatable = false)
    private UUID aiInteractionId;

    @Column(name = "description", columnDefinition = "text", updatable = false)
    private String description;

    @Column(name = "override_classification", updatable = false, nullable = false, length = 30)
    private String overrideClassification;

    @Column(name = "similarity_score", updatable = false, precision = 6, scale = 5)
    private BigDecimal similarityScore;

    @Column(name = "recorded_by", updatable = false, nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", updatable = false, nullable = false)
    private Instant recordedAt;

    protected PhotoDescriptionOverride() {}

    PhotoDescriptionOverride(
            UUID workOrderPhotoId,
            UUID aiInteractionId,
            String description,
            String overrideClassification,
            BigDecimal similarityScore,
            UUID recordedBy,
            Instant recordedAt) {
        this.workOrderPhotoId      = workOrderPhotoId;
        this.aiInteractionId       = aiInteractionId;
        this.description           = description;
        this.overrideClassification = overrideClassification;
        this.similarityScore       = similarityScore;
        this.recordedBy            = recordedBy;
        this.recordedAt            = recordedAt;
    }

    UUID getId() { return id; }
    UUID getWorkOrderPhotoId() { return workOrderPhotoId; }
    UUID getAiInteractionId() { return aiInteractionId; }
    String getDescription() { return description; }
    String getOverrideClassification() { return overrideClassification; }
    BigDecimal getSimilarityScore() { return similarityScore; }
    UUID getRecordedBy() { return recordedBy; }
    Instant getRecordedAt() { return recordedAt; }
}
