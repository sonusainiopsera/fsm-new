package com.fieldservice.domain.user;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An authenticated principal that can log in to the platform.
 *
 * <p>Roles are stored in the {@code user_role} join table, not on this entity.
 * The {@code password_hash} column is CONFIDENTIAL: it must never be logged,
 * serialised to JSON, or exported to non-production environments.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected AppUser() {
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
