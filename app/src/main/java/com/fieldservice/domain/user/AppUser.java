package com.fieldservice.domain.user;

import com.fieldservice.identity.domain.AppearancePreference;
import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * An authenticated principal that can log in to the platform.
 *
 * <p>Roles are stored in the {@code role_assignment} table, not on this entity.
 *
 * <p>Credential classification: {@code password_hash} and {@code external_subject} are
 * CONFIDENTIAL/RESTRICTED — they must never be logged, serialised to JSON, or exported
 * to non-production environments.
 *
 * <p>Q12 forward-compatibility: {@code password_hash} is nullable to accommodate future
 * federated users whose identity is verified via an external IdP ({@code external_subject}).
 * The invitation and federation tables are deferred pending ratification.
 */
@Audited
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    // CONFIDENTIAL — excluded from audit tables per BR-21 and SOC 2 requirements.
    // Nullable: federated users (external_subject) have no local credential.
    // Width 256 holds BCrypt (60 chars), Argon2id, and future algorithm prefix.
    @NotAudited
    @Column(name = "password_hash", nullable = true, length = 256)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // Reserved for future OIDC/SAML federation (Q12 pending ratification).
    // @NotAudited because the sub claim is PII and federation is not yet active.
    @NotAudited
    @Column(name = "external_subject", length = 500)
    private String externalSubject;

    // INTERNAL classification (BR-23). Nullable: NULL resolves to LIGHT client-side.
    // Audited by Envers (no @NotAudited): preference changes are tracked in app_user_aud.
    @Enumerated(EnumType.STRING)
    @Column(name = "appearance_preference", length = 10)
    private AppearancePreference appearancePreference;

    protected AppUser() {
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /** CONFIDENTIAL — never log, never serialise. Nullable for federated users. */
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /** CONFIDENTIAL — OIDC/SAML subject identifier; null until federation is enabled. */
    public String getExternalSubject() { return externalSubject; }
    public void setExternalSubject(String externalSubject) { this.externalSubject = externalSubject; }

    /** INTERNAL — nullable; NULL resolves to LIGHT. Updated via PUT /api/v1/users/me/preferences. */
    public AppearancePreference getAppearancePreference() { return appearancePreference; }
    public void setAppearancePreference(AppearancePreference appearancePreference) {
        this.appearancePreference = appearancePreference;
    }
}
