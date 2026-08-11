package com.fieldservice.privacy.api;

import java.util.List;
import java.util.Optional;

/**
 * Read-only port for querying the data classification registry.
 *
 * <p>Implementations are cached with the Spring Cache abstraction; no caller can
 * mutate registry state through this interface.  Registry reads are safe to call
 * from the worker profile where no HTTP context is present.
 *
 * <p>Returns {@link Optional#empty()} or empty lists when the registry has no row
 * for the requested element (e.g. before the seed migration has run).
 */
public interface ClassificationRegistry {

    /**
     * Returns the entity-level classification for the given simple class name.
     * Only matches rows where {@code field_name IS NULL}.
     */
    Optional<ClassificationView> findByEntity(String entityName);

    /**
     * Returns the field-level classification for the given entity and field name.
     */
    Optional<ClassificationView> findByEntityAndField(String entityName, String fieldName);

    /**
     * Returns all registry rows with the given tier.
     */
    List<ClassificationView> findByTier(ClassificationTier tier);

    /**
     * Returns all registry rows in an unspecified order.
     */
    List<ClassificationView> findAll();
}
