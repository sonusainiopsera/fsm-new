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

@Audited
@Entity
@Table(name = "technician_skill")
class TechnicianSkillEntity {

    @Id
    private UUID id;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proficiency", nullable = false, length = 20)
    private Proficiency proficiency;

    @Column(name = "years_experience")
    private Integer yearsExperience;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected TechnicianSkillEntity() {}

    TechnicianSkillEntity(UUID technicianId, UUID skillId, Proficiency proficiency,
                          Integer yearsExperience) {
        this.id              = UuidV7.generate();
        this.technicianId    = technicianId;
        this.skillId         = skillId;
        this.proficiency     = proficiency;
        this.yearsExperience = yearsExperience;
    }

    UUID        getId()             { return id; }
    UUID        getTechnicianId()   { return technicianId; }
    UUID        getSkillId()        { return skillId; }
    Proficiency getProficiency()    { return proficiency; }
    Integer     getYearsExperience(){ return yearsExperience; }
    Instant     getCreatedAt()      { return createdAt; }
    Integer     getVersion()        { return version; }

    void setProficiency(Proficiency proficiency)     { this.proficiency     = proficiency; }
    void setYearsExperience(Integer yearsExperience) { this.yearsExperience = yearsExperience; }
}
