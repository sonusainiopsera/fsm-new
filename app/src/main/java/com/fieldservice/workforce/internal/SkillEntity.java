package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "skill")
class SkillEntity {

    @Id
    private UUID id;

    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected SkillEntity() {}

    SkillEntity(String code, String displayName) {
        this.id          = UuidV7.generate();
        this.code        = code;
        this.displayName = displayName;
    }

    UUID    getId()          { return id; }
    String  getCode()        { return code; }
    String  getDisplayName() { return displayName; }
    boolean isActive()       { return active; }
    Instant getCreatedAt()   { return createdAt; }
    Integer getVersion()     { return version; }

    void setActive(boolean active)          { this.active = active; }
    void setDisplayName(String displayName) { this.displayName = displayName; }
}
