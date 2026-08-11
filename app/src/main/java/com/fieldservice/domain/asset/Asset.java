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

import java.util.UUID;

/**
 * A physical asset at a customer site (equipment, machinery, installation).
 *
 * <p>Scoped entity: access scope is derived via the owning {@link Site}'s
 * {@code customerId}. CUSTOMER principals see only assets at sites belonging
 * to their customer accounts.
 */
@Entity
@Table(name = "asset")
public class Asset extends BaseEntity implements ScopedEntity {

    @Column(name = "site_id", nullable = false, insertable = false, updatable = false)
    private UUID siteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "asset_type", length = 100)
    private String assetType;

    @Column(name = "serial_no", length = 100)
    private String serialNo;

    @Column(name = "model", length = 255)
    private String model;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Asset() {
    }

    public UUID getSiteId() {
        return siteId;
    }

    public Site getSite() {
        return site;
    }

    public void setSite(Site site) {
        this.site = site;
        this.siteId = site != null ? site.getId() : null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getSerialNo() {
        return serialNo;
    }

    public void setSerialNo(String serialNo) {
        this.serialNo = serialNo;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
