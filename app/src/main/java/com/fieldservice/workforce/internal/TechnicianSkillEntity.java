package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/** Many-to-many link between a technician and a skill with proficiency data. Package-private. */
@Audited
@Entity
@Table(name = "technician_skill")
class TechnicianSkillEntity extends BaseEntity {

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency", nullable = false, length = 20)
    private SkillProficiency proficiency;

    @Column(name = "years_experience")
    private Integer yearsExperience;

    protected TechnicianSkillEntity() {}

    TechnicianSkillEntity(UUID technicianId, UUID skillId, SkillProficiency proficiency,
                           Integer yearsExperience) {
        this.technicianId    = technicianId;
        this.skillId         = skillId;
        this.proficiency     = proficiency;
        this.yearsExperience = yearsExperience;
    }

    UUID getTechnicianId()            { return technicianId; }
    UUID getSkillId()                 { return skillId; }
    SkillProficiency getProficiency() { return proficiency; }
    void setProficiency(SkillProficiency p) { this.proficiency = p; }
    Integer getYearsExperience()      { return yearsExperience; }
    void setYearsExperience(Integer y) { this.yearsExperience = y; }
}
