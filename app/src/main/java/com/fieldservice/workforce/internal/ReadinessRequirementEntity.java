package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Configurable completeness requirement for the certification data-readiness report.
 *
 * <p>{@code PROFILE_FIELD} rows specify a mandatory technician profile field by name.
 * {@code CERTIFICATION_TYPE} rows specify a required certification type code.
 * Only active requirements are used in evaluation. Changes are tracked via Envers.
 */
@Audited
@Entity
@Table(name = "readiness_requirement")
class ReadinessRequirementEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_kind", nullable = false)
    private ReadinessRequirementKind requirementKind;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "certification_type_code")
    private String certificationTypeCode;

    @Column(name = "technician_category")
    private String technicianCategory;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ReadinessRequirementEntity() {}

    ReadinessRequirementEntity(ReadinessRequirementKind kind, String fieldName,
                                String certificationTypeCode, String technicianCategory) {
        this.id                    = UuidV7.generate();
        this.requirementKind       = kind;
        this.fieldName             = fieldName;
        this.certificationTypeCode = certificationTypeCode;
        this.technicianCategory    = technicianCategory;
    }

    UUID getId()                      { return id; }
    ReadinessRequirementKind getRequirementKind() { return requirementKind; }
    String getFieldName()             { return fieldName; }
    String getCertificationTypeCode() { return certificationTypeCode; }
    String getTechnicianCategory()    { return technicianCategory; }
    boolean isActive()                { return active; }
    Integer getVersion()              { return version; }
    Instant getCreatedAt()            { return createdAt; }
    Instant getUpdatedAt()            { return updatedAt; }

    void setFieldName(String fieldName) {
        this.fieldName   = fieldName;
        this.updatedAt   = Instant.now();
    }

    void setCertificationTypeCode(String code) {
        this.certificationTypeCode = code;
        this.updatedAt             = Instant.now();
    }

    void setTechnicianCategory(String category) {
        this.technicianCategory = category;
        this.updatedAt          = Instant.now();
    }

    void setActive(boolean active) {
        this.active    = active;
        this.updatedAt = Instant.now();
    }
}
