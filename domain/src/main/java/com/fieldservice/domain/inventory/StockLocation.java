package com.fieldservice.domain.inventory;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_location")
public class StockLocation implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockLocation() {}

    public StockLocation(String name, Site site) {
        this.id = UuidV7.generate();
        this.name = name;
        this.site = site;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Site getSite() { return site; }
    public Instant getCreatedAt() { return createdAt; }
}
