package com.fieldservice.domain.technician;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.EncryptedStringConverter;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.util.UUID;

/**
 * A field engineer profile linked to an {@link AppUser}.
 *
 * <p>The {@code technician.id} is the row-scope boundary for TECHNICIAN principals:
 * it corresponds to the {@code technicianId} claim in the JWT.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only their own record.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all (technician profiles are not customer-facing).</li>
 * </ul>
 *
 * <p>{@code mobile_phone} is field-encrypted (AES-256-GCM) and carries a
 * CONFIDENTIAL data classification. It must never appear in logs or event payloads.
 */
@DataClassification(tier = ClassificationTier.CONFIDENTIAL,
        note = "Contains PII: mobile_phone (encrypted) and display_name")
@Audited
@Entity
@Table(name = "technician")
public class Technician extends BaseEntity implements ScopedEntity {

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "employee_no", length = 50)
    private String employeeNo;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Staff contact PII — AES-256-GCM encrypted at field level")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mobile_phone")
    private String mobilePhone;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "UTC";

    @Column(name = "home_base_site_id", insertable = false, updatable = false)
    private UUID homeBaseSiteId;

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_base_site_id")
    private com.fieldservice.domain.site.Site homeBaseSite;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Technician() {
    }

    public UUID getUserId() { return userId; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) {
        this.user = user;
        this.userId = user != null ? user.getId() : null;
    }

    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getMobilePhone() { return mobilePhone; }
    public void setMobilePhone(String mobilePhone) { this.mobilePhone = mobilePhone; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone != null ? timezone : "UTC"; }

    public UUID getHomeBaseSiteId() { return homeBaseSiteId; }
    public void setHomeBaseSite(com.fieldservice.domain.site.Site site) {
        this.homeBaseSite = site;
        this.homeBaseSiteId = site != null ? site.getId() : null;
    }

    /** @deprecated Use {@link #getMobilePhone()} for the workforce-profile phone. */
    @Deprecated
    public String getPhone() { return phone; }
    /** @deprecated Use {@link #setMobilePhone(String)} for the workforce-profile phone. */
    @Deprecated
    public void setPhone(String phone) { this.phone = phone; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
