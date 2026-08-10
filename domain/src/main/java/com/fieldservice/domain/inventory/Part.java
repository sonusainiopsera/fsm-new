package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "part")
public class Part implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "part_number", nullable = false, length = 50, unique = true)
    private String partNumber;

    @Column(length = 500)
    private String description;

    @Column(name = "unit_cost", precision = 10, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Part() {}

    public Part(String partNumber, String description, BigDecimal unitCost) {
        this.id = UuidV7.generate();
        this.partNumber = partNumber;
        this.description = description;
        this.unitCost = unitCost;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPartNumber() { return partNumber; }
    public String getDescription() { return description; }
    public BigDecimal getUnitCost() { return unitCost; }
    public Instant getCreatedAt() { return createdAt; }
}
