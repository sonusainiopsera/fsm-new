package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "part")
public class Part {

    @Id
    private UUID id;

    @Column(name = "part_number", nullable = false, length = 100, unique = true)
    private String partNumber;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 4000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Part() {}

    public Part(String partNumber, String name) {
        this.id         = UuidV7.generate();
        this.partNumber = partNumber;
        this.name       = name;
    }

    public UUID    getId()          { return id; }
    public String  getPartNumber()  { return partNumber; }
    public String  getName()        { return name; }
    public String  getDescription() { return description; }
    public Instant getCreatedAt()   { return createdAt; }
}
