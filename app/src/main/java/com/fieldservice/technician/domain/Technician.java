package com.fieldservice.technician.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "technician")
public class Technician {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(length = 50)
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected Technician() {}

    public Technician(UUID userId, String fullName) {
        this.id       = UuidV7.generate();
        this.userId   = userId;
        this.fullName = fullName;
    }

    public UUID    getId()        { return id; }
    public UUID    getUserId()    { return userId; }
    public String  getFullName()  { return fullName; }
    public String  getPhone()     { return phone; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getVersion()   { return version; }
}
