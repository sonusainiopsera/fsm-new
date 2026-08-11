package com.fieldservice.workforce.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/** Runtime-configurable certification type catalogue entry. Package-private. */
@Audited
@Entity
@Table(name = "certification_type")
class CertificationTypeEntity extends BaseEntity {

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

    protected CertificationTypeEntity() {}

    CertificationTypeEntity(String code, String displayName, boolean regulated,
                             Integer defaultValidityMonths) {
        this.code                  = code;
        this.displayName           = displayName;
        this.regulated             = regulated;
        this.defaultValidityMonths = defaultValidityMonths;
    }

    String  getCode()                   { return code; }
    void    setCode(String code)        { this.code = code; }
    String  getDisplayName()            { return displayName; }
    void    setDisplayName(String n)    { this.displayName = n; }
    boolean isRegulated()               { return regulated; }
    void    setRegulated(boolean r)     { this.regulated = r; }
    Integer getDefaultValidityMonths()  { return defaultValidityMonths; }
    void    setDefaultValidityMonths(Integer m) { this.defaultValidityMonths = m; }
    boolean isActive()                  { return active; }
    void    setActive(boolean a)        { this.active = a; }
}
