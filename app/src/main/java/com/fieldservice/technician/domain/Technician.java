package com.fieldservice.technician.domain;

import com.fieldservice.platform.crypto.EncryptedStringConverter;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.util.UUID;

@DataClassification(value = ClassificationTier.CONFIDENTIAL, module = "technician")
@Audited
@Entity
@Table(name = "technician")
public class Technician {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @DataClassification(value = ClassificationTier.CONFIDENTIAL, module = "technician")
    @Column(name = "full_name", length = 255)
    private String fullName;

    @DataClassification(value = ClassificationTier.CONFIDENTIAL, module = "technician")
    @Column(length = 50)
    private String phone;

    // Extended workforce profile fields (V28)

    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @DataClassification(value = ClassificationTier.CONFIDENTIAL, module = "technician")
    @NotAudited
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mobile_phone", columnDefinition = "TEXT")
    private String mobilePhone;

    @Column(name = "timezone", length = 100, nullable = false)
    private String timezone = "UTC";

    @Column(name = "home_base_site_id")
    private UUID homeBaseSiteId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Integer version;

    protected Technician() {}

    public Technician(UUID userId, String fullName) {
        this.id       = UuidV7.generate();
        this.userId   = userId;
        this.fullName = fullName;
    }

    public Technician(UUID userId, String employeeCode, String displayName, String timezone) {
        this.id           = UuidV7.generate();
        this.userId       = userId;
        this.employeeCode = employeeCode;
        this.displayName  = displayName;
        this.fullName     = displayName;
        this.timezone     = timezone != null ? timezone : "UTC";
    }

    public UUID    getId()             { return id; }
    public UUID    getUserId()         { return userId; }
    public String  getFullName()       { return fullName; }
    public String  getPhone()          { return phone; }
    public String  getEmployeeCode()   { return employeeCode; }
    public String  getDisplayName()    { return displayName; }
    public String  getMobilePhone()    { return mobilePhone; }
    public String  getTimezone()       { return timezone; }
    public UUID    getHomeBaseSiteId() { return homeBaseSiteId; }
    public boolean isActive()          { return active; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getUpdatedAt()      { return updatedAt; }
    public Integer getVersion()        { return version; }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.fullName    = displayName;
        this.updatedAt   = Instant.now();
    }

    public void setMobilePhone(String mobilePhone) {
        this.mobilePhone = mobilePhone;
        this.updatedAt   = Instant.now();
    }

    public void setTimezone(String timezone) {
        this.timezone  = timezone != null ? timezone : "UTC";
        this.updatedAt = Instant.now();
    }

    public void setHomeBaseSiteId(UUID homeBaseSiteId) {
        this.homeBaseSiteId = homeBaseSiteId;
        this.updatedAt      = Instant.now();
    }

    public void setActive(boolean active) {
        this.active    = active;
        this.updatedAt = Instant.now();
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
        this.updatedAt    = Instant.now();
    }
}
