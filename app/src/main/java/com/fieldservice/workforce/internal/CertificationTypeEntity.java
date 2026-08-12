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

/**
 * Runtime-configurable certification type.
 *
 * <p>{@code regulated = true} means the type is a hard compliance gate —
 * assignment is refused with 422 when the required certification is not current.
 * {@code regulated = false} means advisory — a warning is returned but assignment proceeds.
 *
 * <p>The taxonomy is unratified (open question Q4 2026); seeded values are visibly
 * labelled as {@code [PLACEHOLDER]}.  Taxonomy changes require only a data migration,
 * not a code release.
 */
@Audited
@Entity
@Table(name = "certification_type")
class CertificationTypeEntity {

    @Id
    private UUID id;

    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "regulated", nullable = false)
    private boolean regulated = false;

    @Column(name = "default_validity_months")
    private Integer defaultValidityMonths;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected CertificationTypeEntity() {}

    CertificationTypeEntity(String code, String displayName, boolean regulated,
                            Integer defaultValidityMonths) {
        this.id                   = UuidV7.generate();
        this.code                 = code;
        this.displayName          = displayName;
        this.regulated            = regulated;
        this.defaultValidityMonths = defaultValidityMonths;
    }

    UUID    getId()                    { return id; }
    String  getCode()                  { return code; }
    String  getDisplayName()           { return displayName; }
    boolean isRegulated()              { return regulated; }
    Integer getDefaultValidityMonths() { return defaultValidityMonths; }
    boolean isActive()                 { return active; }
    Instant getCreatedAt()             { return createdAt; }
    Integer getVersion()               { return version; }

    void setDisplayName(String displayName)              { this.displayName = displayName; }
    void setRegulated(boolean regulated)                 { this.regulated = regulated; }
    void setDefaultValidityMonths(Integer months)        { this.defaultValidityMonths = months; }
    void setActive(boolean active)                       { this.active = active; }
}
