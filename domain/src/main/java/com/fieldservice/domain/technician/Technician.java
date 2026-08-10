package com.fieldservice.domain.technician;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "technician")
public class Technician implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 320)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Technician() {}

    public Technician(String fullName, String email, String phone, UUID userId) {
        this.id = UuidV7.generate();
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
