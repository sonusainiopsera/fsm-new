package com.fieldservice.privacy.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RetentionPolicy}.
 *
 * <p>Package-private: no caller outside this package may reference this interface.
 * All public access goes through {@link com.fieldservice.privacy.api.RetentionPolicyAdminPort}.
 *
 * <p>No {@code ScopedRepository} implementation required: retention_policy rows are
 * aggregate metadata with no per-user row scope; access is controlled at the
 * method-security layer ({@code @PreAuthorize} on the admin service).
 */
interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, UUID> {

    Optional<RetentionPolicy> findByDataCategory(String dataCategory);

    boolean existsByEntityName(String entityName);

    Page<RetentionPolicy> findAllByOrderByDataCategoryAscIdAsc(Pageable pageable);

    List<RetentionPolicy> findByRatifiedTrueAndEnabledTrueAndLegalHoldFalse();
}
