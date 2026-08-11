package com.fieldservice.workforce.internal;

import com.fieldservice.platform.crypto.EncryptedStringConverter;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Last-known position for a technician.  Not Envers-audited — retained for a configurable
 * maximum of 90 days and purged by a scheduled job.  Latitude and longitude are
 * field-encrypted to satisfy the Confidential data classification.
 */
@Entity
@Table(name = "technician_position")
class TechnicianPositionEntity {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "latitude", nullable = false, columnDefinition = "TEXT")
    private String latitude;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "longitude", nullable = false, columnDefinition = "TEXT")
    private String longitude;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected TechnicianPositionEntity() {}

    TechnicianPositionEntity(UUID technicianId, String latitude, String longitude,
                             Instant capturedAt) {
        this.id           = UuidV7.generate();
        this.technicianId = technicianId;
        this.latitude     = latitude;
        this.longitude    = longitude;
        this.capturedAt   = capturedAt;
    }

    UUID    getId()           { return id; }
    UUID    getTechnicianId() { return technicianId; }
    String  getLatitude()     { return latitude; }
    String  getLongitude()    { return longitude; }
    Instant getCapturedAt()   { return capturedAt; }
}
