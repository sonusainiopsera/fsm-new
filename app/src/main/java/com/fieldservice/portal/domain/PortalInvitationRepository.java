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
}
