package com.fieldservice.domain.asset;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "assets")
public class Asset implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "asset_type", length = 100)
    private String assetType;

    protected Asset() {}

    public Asset(String name, Site site, String assetType) {
        this.name = name;
        this.site = site;
        this.assetType = assetType;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Site getSite() { return site; }
    public String getAssetType() { return assetType; }
}
