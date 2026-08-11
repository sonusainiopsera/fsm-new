package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/** Skill reference-data entry. Package-private — access only through {@code WorkforceService}. */
@Audited
@Entity
@Table(name = "skill")
class SkillEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected SkillEntity() {}

    SkillEntity(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    String getCode() { return code; }
    void setCode(String code) { this.code = code; }

    String getDisplayName() { return displayName; }
    void setDisplayName(String displayName) { this.displayName = displayName; }

    boolean isActive() { return active; }
    void setActive(boolean active) { this.active = active; }
}
