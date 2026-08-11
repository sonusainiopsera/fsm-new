package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.EncryptedStringConverter;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Last-known GPS position for a technician.
 *
 * <p>Latitude and longitude are field-encrypted (AES-256-GCM) and classified
 * CONFIDENTIAL. They must never appear in logs or event payloads.
 *
 * <p>One row per technician (enforced by UNIQUE constraint). Package-private.
 */
@Entity
@Table(name = "technician_position")
class TechnicianPositionEntity extends BaseEntity {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Location PII — AES-256-GCM encrypted")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "latitude", nullable = false)
    private String latitude;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Location PII — AES-256-GCM encrypted")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "longitude", nullable = false)
    private String longitude;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected TechnicianPositionEntity() {}

    TechnicianPositionEntity(UUID technicianId, String latitude, String longitude,
                              Instant capturedAt) {
        this.technicianId = technicianId;
        this.latitude     = latitude;
        this.longitude    = longitude;
        this.capturedAt   = capturedAt;
    }

    UUID getTechnicianId()       { return technicianId; }
    String getLatitude()         { return latitude; }
    void setLatitude(String lat) { this.latitude = lat; }
    String getLongitude()        { return longitude; }
    void setLongitude(String lon){ this.longitude = lon; }
    Instant getCapturedAt()      { return capturedAt; }
    void setCapturedAt(Instant t){ this.capturedAt = t; }
}
