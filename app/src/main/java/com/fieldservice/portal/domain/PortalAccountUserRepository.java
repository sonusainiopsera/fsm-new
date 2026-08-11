package com.fieldservice.portal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PortalAccountUser} linkage rows.
 *
 * <p>This repository extends {@link JpaRepository} directly (not {@link
 * com.fieldservice.platform.persistence.ScopedRepository}) because it is
 * scope-resolution infrastructure, not a domain aggregate exposed via a
 * customer-facing query path. Access is guarded at the service layer:
 * only {@link com.fieldservice.portal.access.CustomerAccessScope} and
 * {@link com.fieldservice.portal.invitation.InvitationService} may call it.
 */
public interface PortalAccountUserRepository extends JpaRepository<PortalAccountUser, UUID> {

    Optional<PortalAccountUser> findByUserId(UUID userId);
}
