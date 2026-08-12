package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * Configurable completeness requirement for the certification data-readiness gate.
 *
 * <p>PROFILE_FIELD kind: the named technician profile field must be non-null/non-blank.
 * CERTIFICATION_TYPE kind: technician must hold a current certification of this type.
 *
 * <p>Audited by Hibernate Envers so definition changes are traceable per AC-1.
 */
@Audited
@Entity
@Table(name = "readiness_requirement")
class ReadinessRequirementEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_kind", nullable = false, length = 30)
    private RequirementKind requirementKind;

    @Column(name = "field_name", length = 100)
    private String fieldName;

    @Column(name = "certification_type_code", length = 50)
    private String certificationTypeCode;

    @Column(name = "technician_category", length = 100)
    private String technicianCategory;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ReadinessRequirementEntity() {}

    ReadinessRequirementEntity(RequirementKind kind, String fieldName,
                                String certTypeCode, String technicianCategory) {
        this.requirementKind       = kind;
        this.fieldName             = fieldName;
        this.certificationTypeCode = certTypeCode;
        this.technicianCategory    = technicianCategory;
    }

    RequirementKind getRequirementKind()                  { return requirementKind; }
    void            setRequirementKind(RequirementKind k) { this.requirementKind = k; }
    String          getFieldName()                        { return fieldName; }
    void            setFieldName(String f)                { this.fieldName = f; }
    String          getCertificationTypeCode()            { return certificationTypeCode; }
    void            setCertificationTypeCode(String c)    { this.certificationTypeCode = c; }
    String          getTechnicianCategory()               { return technicianCategory; }
    void            setTechnicianCategory(String c)       { this.technicianCategory = c; }
    boolean         isActive()                            { return active; }
    void            setActive(boolean a)                  { this.active = a; }
}
