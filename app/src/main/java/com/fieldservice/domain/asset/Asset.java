package com.fieldservice.domain.asset;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A physical asset at a customer site (equipment, machinery, installation).
 *
 * <p>Data classification: Internal. {@code asset_tag} is the join key for the
 * first-time-fix KPI — weak asset identity silently corrupts the O3 headline metric.
 *
 * <p>Scoped entity: access scope is derived via the owning {@link Site}'s
 * {@code customerId}. CUSTOMER principals see only assets at sites belonging
 * to their customer accounts.
 */
@Audited
@Entity
@Table(name = "asset")
public class Asset extends BaseEntity implements ScopedEntity {

    @Column(name = "site_id", nullable = false, insertable = false, updatable = false)
    private UUID siteId;

    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    /** Legacy name column from V1 — kept for backward compat. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Legacy type column from V1. */
    @Column(name = "asset_type", length = 100)
    private String assetType;

    @Column(name = "serial_no", length = 100)
    private String serialNo;

    @Column(name = "model", length = 255)
    private String model;

    /** Asset tag label — unique per active site; the join key for first-time-fix. */
    @Column(name = "asset_tag", length = 100)
    private String assetTag;

    @Column(name = "manufacturer", length = 100)
    private String manufacturer;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "installed_on")
    private LocalDate installedOn;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected Asset() {
    }

    public UUID getSiteId() { return siteId; }

    public Site getSite() { return site; }

    public void setSite(Site site) {
        this.site = site;
        this.siteId = site != null ? site.getId() : null;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }

    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getAssetTag() { return assetTag; }
    public void setAssetTag(String assetTag) { this.assetTag = assetTag; }

    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public LocalDate getInstalledOn() { return installedOn; }
    public void setInstalledOn(LocalDate installedOn) { this.installedOn = installedOn; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }
}
