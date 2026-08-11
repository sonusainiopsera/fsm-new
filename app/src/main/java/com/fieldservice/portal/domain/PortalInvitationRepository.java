package com.fieldservice.portal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PortalInvitation} rows.
 *
 * <p>Extends {@link JpaRepository} directly (not ScopedRepository) because invitations are
 * infrastructure records accessed only by {@link com.fieldservice.portal.invitation.InvitationService}.
 * Row-scope enforcement is not applicable: lookups are by token_hash (single-use, not user-bound),
 * and issuance is guarded by method-security annotations requiring ADMIN or DISPATCHER role.
 */
public interface PortalInvitationRepository extends JpaRepository<PortalInvitation, UUID> {

    Optional<PortalInvitation> findByTokenHash(String tokenHash);

    /**
     * Finds an invitation by the HMAC-SHA-256 blind index of the contact email.
     * Use {@link com.fieldservice.platform.crypto.BlindIndex#compute(String)} to derive the index
     * from a normalised plaintext email before calling this method.
     * Range/prefix/sort searches over encrypted email are unsupported.
     */
    Optional<PortalInvitation> findByContactEmailIdx(String contactEmailIdx);
}
