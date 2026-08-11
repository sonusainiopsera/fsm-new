package com.fieldservice.privacy.api;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Read-only public port for querying the data classification registry.
 *
 * <p>Implementations are cache-backed; reads are side-effect-free and safe to call
 * from the worker profile where no HTTP context exists.
 *
 * <p>Callers in other bounded contexts (e.g. log masking, encryption) must depend on
 * this interface only — never on the internal entity or repository.
 */
public interface ClassificationRegistry {

    /**
     * Returns the entity-level classification for the given entity name, or empty if
     * no registry row exists for that entity at entity level (field_name IS NULL).
     */
    Optional<ClassificationView> findByEntity(String entityName);

    /**
     * Returns the field-level classification for the given entity and field, or empty if
     * not found. Pass {@code null} for {@code fieldName} to look up the entity-level row.
     */
    Optional<ClassificationView> findByEntityAndField(String entityName, @Nullable String fieldName);

    /**
     * Returns all registry rows for the given tier. The result is ordered by entity_name
     * then field_name for determinism.
     */
    List<ClassificationView> findByTier(ClassificationTier tier);

    /**
     * Returns every row in the registry, ordered by entity_name then field_name.
     * Used by the startup consistency check and the admin list API.
     */
    List<ClassificationView> findAll();
}
