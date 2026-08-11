package com.fieldservice.domain.asset;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "asset")
public class Asset implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "asset_type", length = 100)
    private String assetType;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    protected Asset() {}

    public Asset(String name, Site site, String assetType) {
        this.id = UuidV7.generate();
        this.name = name;
        this.site = site;
        this.assetType = assetType;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Site getSite() { return site; }
    public String getAssetType() { return assetType; }
    public String getSerialNumber() { return serialNumber; }
}
