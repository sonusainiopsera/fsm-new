package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationTier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ClassificationEntity}.
 *
 * <p>Package-private: no caller outside this package may reference this interface.
 * The {@link ClassificationRegistryImpl} is the only consumer; all public access
 * goes through {@link com.fieldservice.privacy.api.ClassificationRegistry} or
 * {@link com.fieldservice.privacy.api.ClassificationAdminPort}.
 *
 * <p>No {@code ScopedRepository} implementation required: classification rows are
 * aggregate metadata with no per-user row scope. Access is controlled at the
 * method-security layer (@PreAuthorize on the admin service).
 */
interface ClassificationRepository extends JpaRepository<ClassificationEntity, UUID> {

    Optional<ClassificationEntity> findByEntityNameAndFieldNameIsNull(String entityName);

    Optional<ClassificationEntity> findByEntityNameAndFieldName(String entityName, String fieldName);

    List<ClassificationEntity> findByTierOrderByEntityNameAscFieldNameAsc(ClassificationTier tier);

    List<ClassificationEntity> findAllByOrderByEntityNameAscFieldNameAsc();

    Page<ClassificationEntity> findByTierOrderByEntityNameAscFieldNameAscIdAsc(
            ClassificationTier tier, Pageable pageable);

    Page<ClassificationEntity> findAllByOrderByEntityNameAscFieldNameAscIdAsc(Pageable pageable);

    boolean existsByEntityNameAndFieldName(String entityName, @Nullable String fieldName);
}
