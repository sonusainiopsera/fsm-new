package com.fieldservice.domain.customer;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class Customer implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public Customer(String name, String contactEmail, String contactPhone) {
        this.id = UuidV7.generate();
        this.name = name;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
