package com.fieldservice.workforce.internal;

import com.fieldservice.platform.crypto.BlindIndex;
import com.fieldservice.platform.crypto.EnvelopeEncryptedStringConverter;
import com.fieldservice.platform.crypto.SubjectKeyContextListener;
import com.fieldservice.platform.crypto.SubjectKeyContextProvider;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Last-known GPS position for a technician.
 *
 * <p>Latitude and longitude are field-encrypted (AES-256-GCM per-subject envelope) and
 * classified CONFIDENTIAL. They must never appear in logs or event payloads.
 * Blind-index columns (HMAC-SHA-256) allow equality lookup without plaintext exposure;
 * range and prefix searches over these fields are unsupported.
 *
 * <p>One row per technician (enforced by UNIQUE constraint). Package-private.
 */
@Audited
@Entity
@EntityListeners(SubjectKeyContextListener.class)
@Table(name = "technician_position")
class TechnicianPositionEntity extends BaseEntity implements SubjectKeyContextProvider {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Location PII — AES-256-GCM envelope encrypted; key destroyable per WO-193")
    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "latitude", nullable = false, length = 512)
    private String latitude;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Location PII — AES-256-GCM envelope encrypted; key destroyable per WO-193")
    @Convert(converter = EnvelopeEncryptedStringConverter.class)
    @Column(name = "longitude", nullable = false, length = 512)
    private String longitude;

    /** HMAC-SHA-256 blind index for latitude equality lookup (range/sort unsupported). */
    @Column(name = "latitude_idx", length = 64)
    private String latitudeIdx;

    /** HMAC-SHA-256 blind index for longitude equality lookup (range/sort unsupported). */
    @Column(name = "longitude_idx", length = 64)
    private String longitudeIdx;

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

    @Override
    public String getEnvelopeSubjectType() { return "TECHNICIAN"; }

    @Override
    public UUID getEnvelopeSubjectId() { return technicianId; }

    @Override
    public void recomputeBlindIndices() {
        this.latitudeIdx  = BlindIndex.compute(latitude);
        this.longitudeIdx = BlindIndex.compute(longitude);
    }

    UUID getTechnicianId()       { return technicianId; }
    String getLatitude()         { return latitude; }
    void setLatitude(String lat) { this.latitude = lat; }
    String getLongitude()        { return longitude; }
    void setLongitude(String lon){ this.longitude = lon; }
    String getLatitudeIdx()      { return latitudeIdx; }
    String getLongitudeIdx()     { return longitudeIdx; }
    Instant getCapturedAt()      { return capturedAt; }
    void setCapturedAt(Instant t){ this.capturedAt = t; }
}
