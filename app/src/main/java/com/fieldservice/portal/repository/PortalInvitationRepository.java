package com.fieldservice.portal.repository;

import com.fieldservice.portal.domain.PortalInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Repository for {@link PortalInvitation}. */
public interface PortalInvitationRepository extends JpaRepository<PortalInvitation, UUID> {

    Optional<PortalInvitation> findByTokenHash(String tokenHash);
}
