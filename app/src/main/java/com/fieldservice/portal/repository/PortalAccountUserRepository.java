package com.fieldservice.portal.repository;

import com.fieldservice.platform.persistence.ScopedRepository;
import com.fieldservice.portal.domain.PortalAccountUser;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PortalAccountUser}.
 *
 * <p>Extends {@link ScopedRepository} so every read is subject to the
 * {@link com.fieldservice.portal.access.PortalAccountUserScopeSpec} predicate enforced by
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}. Direct
 * {@link org.springframework.data.jpa.repository.JpaRepository} access on scoped entities
 * is forbidden by ArchUnit fitness tests.
 *
 * <p>The additional {@link #findByUserId} method is used by
 * {@link com.fieldservice.portal.access.CustomerAccessScope} to resolve the customer's
 * account id directly from the authenticated principal without going through the scope
 * predicate (which would create a circular dependency).
 */
public interface PortalAccountUserRepository extends ScopedRepository<PortalAccountUser, UUID> {

    /**
     * Looks up the portal account user by their identity user id.
     * Used by CustomerAccessScope to resolve the account linkage for the current principal.
     */
    Optional<PortalAccountUser> findByUserId(UUID userId);
}
