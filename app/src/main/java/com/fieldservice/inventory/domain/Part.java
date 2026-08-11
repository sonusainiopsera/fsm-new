package com.fieldservice.inventory.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "part")
public class Part implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "part_number", nullable = false, length = 100, unique = true)
    private String partNumber;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 4000)
    private String description;

    @Column(name = "unit_of_measure", length = 50)
    private String unitOfMeasure;

    @Column(name = "reorder_point", nullable = false)
    private Integer reorderPoint = 0;

    @Column(name = "reorder_quantity", nullable = false)
    private Integer reorderQuantity = 0;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Part() {}

    public Part(String partNumber, String name) {
        this.id         = UuidV7.generate();
        this.partNumber = partNumber;
        this.name       = name;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID    getId()              { return id; }
    public String  getPartNumber()      { return partNumber; }
    public String  getName()            { return name; }
    public String  getDescription()     { return description; }
    public String  getUnitOfMeasure()   { return unitOfMeasure; }
    public Integer getReorderPoint()    { return reorderPoint; }
    public Integer getReorderQuantity() { return reorderQuantity; }
    public Boolean getActive()          { return active; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
